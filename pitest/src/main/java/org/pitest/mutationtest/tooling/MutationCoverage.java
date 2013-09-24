/*
 * Copyright 2010 Henry Coles
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.mutationtest.tooling;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.pitest.classinfo.ClassInfo;
import org.pitest.classinfo.ClassName;
import org.pitest.classinfo.HierarchicalClassId;
import org.pitest.classpath.ClassPathByteArraySource;
import org.pitest.classpath.CodeSource;
import org.pitest.coverage.CoverageDatabase;
import org.pitest.coverage.CoverageGenerator;
import org.pitest.coverage.domain.TestInfo;
import org.pitest.execute.Container;
import org.pitest.execute.DefaultStaticConfig;
import org.pitest.execute.Pitest;
import org.pitest.execute.containers.BaseThreadPoolContainer;
import org.pitest.execute.containers.ClassLoaderFactory;
import org.pitest.execute.containers.UnContainer;
import org.pitest.functional.FCollection;
import org.pitest.functional.Prelude;
import org.pitest.help.Help;
import org.pitest.help.PitHelpError;
import org.pitest.mutationtest.ListenerArguments;
import org.pitest.mutationtest.MutationAnalyser;
import org.pitest.mutationtest.MutationConfig;
import org.pitest.mutationtest.MutationResultAdapter;
import org.pitest.mutationtest.MutationResultListener;
import org.pitest.mutationtest.MutationStrategies;
import org.pitest.mutationtest.ReportOptions;
import org.pitest.mutationtest.SmartSourceLocator;
import org.pitest.mutationtest.SpinnerListener;
import org.pitest.mutationtest.build.MutationSource;
import org.pitest.mutationtest.build.MutationTestBuilder;
import org.pitest.mutationtest.engine.MutationEngine;
import org.pitest.mutationtest.filter.LimitNumberOfMutationPerClassFilter;
import org.pitest.mutationtest.filter.MutationFilterFactory;
import org.pitest.mutationtest.filter.UnfilteredMutationFilter;
import org.pitest.mutationtest.incremental.DefaultCodeHistory;
import org.pitest.mutationtest.incremental.HistoryListener;
import org.pitest.mutationtest.incremental.HistoryStore;
import org.pitest.mutationtest.incremental.IncrementalAnalyser;
import org.pitest.mutationtest.statistics.MutationStatistics;
import org.pitest.mutationtest.statistics.MutationStatisticsListener;
import org.pitest.mutationtest.statistics.Score;
import org.pitest.testapi.TestUnit;
import org.pitest.util.IsolationUtils;
import org.pitest.util.Log;
import org.pitest.util.StringUtil;
import org.pitest.util.Timings;

public class MutationCoverage {

  private final static int         MB  = 1024 * 1024;

  private static final Logger      LOG = Log.getLogger();
  private final ReportOptions      data;

  private final MutationStrategies strategies;
  private final Timings            timings;
  private final CodeSource         code;
  private final File               baseDir;

  public MutationCoverage(final MutationStrategies strategies,
      final File baseDir, final CodeSource code, final ReportOptions data,
      final Timings timings) {
    this.strategies = strategies;
    this.data = data;
    this.timings = timings;
    this.code = code;
    this.baseDir = baseDir;
  }

  public MutationStatistics runReport() throws IOException {

    Log.setVerbose(this.data.isVerbose());

    final Runtime runtime = Runtime.getRuntime();

    if (!this.data.isVerbose()) {
      LOG.info("Verbose logging is disabled. If you encounter an problem please enable it before reporting an issue.");
    }

    LOG.fine("Running report with " + this.data);

    LOG.fine("System class path is " + System.getProperty("java.class.path"));
    LOG.fine("Maxmium available memory is " + (runtime.maxMemory() / MB)
        + " mb");

    final long t0 = System.currentTimeMillis();

    verifyBuildSuitableForMutationTesting();

    final CoverageDatabase coverageData = coverage().calculateCoverage();

    LOG.fine("Used memory after coverage calculation "
        + ((runtime.totalMemory() - runtime.freeMemory()) / MB) + " mb");
    LOG.fine("Free Memory after coverage calculation "
        + (runtime.freeMemory() / MB) + " mb");

    final MutationStatisticsListener stats = new MutationStatisticsListener();
    final DefaultStaticConfig staticConfig = createConfig(t0, coverageData,
        stats);

    history().initialize();

    this.timings.registerStart(Timings.Stage.BUILD_MUTATION_TESTS);
    final List<? extends TestUnit> tus = buildMutationTests(coverageData);
    this.timings.registerEnd(Timings.Stage.BUILD_MUTATION_TESTS);

    LOG.info("Created  " + tus.size() + " mutation test units");
    checkMutationsFound(tus);

    recordClassPath(coverageData);

    LOG.fine("Used memory before analysis start "
        + ((runtime.totalMemory() - runtime.freeMemory()) / MB) + " mb");
    LOG.fine("Free Memory before analysis start " + (runtime.freeMemory() / MB)
        + " mb");

    final Pitest pit = new Pitest(staticConfig);
    this.timings.registerStart(Timings.Stage.RUN_MUTATION_TESTS);
    pit.run(createContainer(), tus);
    this.timings.registerEnd(Timings.Stage.RUN_MUTATION_TESTS);

    LOG.info("Completed in " + timeSpan(t0));

    printStats(stats);

    return stats.getStatistics();

  }

  private DefaultStaticConfig createConfig(final long t0,
      final CoverageDatabase coverageData,
      final MutationStatisticsListener stats) {
    final DefaultStaticConfig staticConfig = new DefaultStaticConfig();

    staticConfig.addTestListener(MutationResultAdapter.adapt(stats));

    final ListenerArguments args = new ListenerArguments(strategies.output(), coverageData, 
        new SmartSourceLocator(this.data.getSourceDirs()), t0);
    
    final MutationResultListener mutationReportListener = this.strategies
        .listenerFactory().getListener(args);

    staticConfig.addTestListener(MutationResultAdapter
        .adapt(mutationReportListener));
    staticConfig.addTestListener(MutationResultAdapter
        .adapt(new HistoryListener(history())));

    if (!this.data.isVerbose()) {
      staticConfig.addTestListener(MutationResultAdapter
          .adapt(new SpinnerListener(System.out)));
    }
    return staticConfig;
  }

  private void recordClassPath(final CoverageDatabase coverageData) {
    final Set<ClassName> allClassNames = getAllClassesAndTests(coverageData);
    final Collection<HierarchicalClassId> ids = FCollection.map(
        this.code.getClassInfo(allClassNames), ClassInfo.toFullClassId());
    history().recordClassPath(ids, coverageData);
  }

  private Set<ClassName> getAllClassesAndTests(
      final CoverageDatabase coverageData) {
    final Set<ClassName> names = new HashSet<ClassName>();
    for (final ClassName each : this.code.getCodeUnderTestNames()) {
      names.add(each);
      FCollection.mapTo(coverageData.getTestsForClass(each),
          TestInfo.toDefiningClassName(), names);
    }
    return names;
  }

  private void verifyBuildSuitableForMutationTesting() {
    this.strategies.buildVerifier().verify(this.code);
  }

  private void printStats(final MutationStatisticsListener stats) {
    final PrintStream ps = System.out;
    ps.println(StringUtil.seperatorLine('='));
    ps.println("- Timings");
    ps.println(StringUtil.seperatorLine('='));
    this.timings.report(ps);

    ps.println(StringUtil.seperatorLine('='));
    ps.println("- Statistics");
    ps.println(StringUtil.seperatorLine('='));
    stats.getStatistics().report(ps);

    ps.println(StringUtil.seperatorLine('='));
    ps.println("- Mutators");
    ps.println(StringUtil.seperatorLine('='));
    for (final Score each : stats.getStatistics().getScores()) {
      each.report(ps);
      ps.println(StringUtil.seperatorLine());
    }
  }

  private List<? extends TestUnit> buildMutationTests(
      final CoverageDatabase coverageData) {
    final MutationEngine engine = this.strategies.factory().createEngine(
        this.data.isMutateStaticInitializers(),
        Prelude.or(this.data.getExcludedMethods()),
        this.data.getLoggingClasses(), this.data.getMutators(),
        this.data.isDetectInlinedCode());

    final MutationConfig mutationConfig = new MutationConfig(engine,
        this.data.getJvmArgs());

    final MutationSource source = new MutationSource(mutationConfig,
        limitMutationsPerClass(), coverageData, new ClassPathByteArraySource(
            this.data.getClassPath()));

    final MutationAnalyser analyser = new IncrementalAnalyser(
        new DefaultCodeHistory(this.code, history()), coverageData);

    final MutationTestBuilder builder = new MutationTestBuilder(this.baseDir,
        mutationConfig, analyser, source, this.data, coverage()
            .getConfiguration(), coverage().getJavaAgent());

    return builder.createMutationTestUnits(this.code.getCodeUnderTestNames());
  }

  private void checkMutationsFound(final List<? extends TestUnit> tus) {
    if (tus.isEmpty()) {
      if (this.data.shouldFailWhenNoMutations()) {
        throw new PitHelpError(Help.NO_MUTATIONS_FOUND);
      } else {
        LOG.warning(Help.NO_MUTATIONS_FOUND.toString());
      }
    }
  }

  private MutationFilterFactory limitMutationsPerClass() {
    if (this.data.getMaxMutationsPerClass() <= 0) {
      return UnfilteredMutationFilter.factory();
    } else {
      return LimitNumberOfMutationPerClassFilter.factory(this.data
          .getMaxMutationsPerClass());
    }

  }

  private Container createContainer() {
    if (this.data.getNumberOfThreads() > 1) {
      return new BaseThreadPoolContainer(this.data.getNumberOfThreads(),
          classLoaderFactory(), Executors.defaultThreadFactory()) {
      };
    } else {
      return new UnContainer();
    }
  }

  private ClassLoaderFactory classLoaderFactory() {
    final ClassLoader loader = IsolationUtils.getContextClassLoader();
    return new ClassLoaderFactory() {

      public ClassLoader get() {
        return loader;
      }

    };
  }

  private String timeSpan(final long t0) {
    return "" + ((System.currentTimeMillis() - t0) / 1000) + " seconds";
  }

  private CoverageGenerator coverage() {
    return this.strategies.coverage();
  }

  private HistoryStore history() {
    return this.strategies.history();
  }

}
