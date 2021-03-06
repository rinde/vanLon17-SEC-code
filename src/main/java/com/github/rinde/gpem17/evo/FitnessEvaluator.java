/*
 * Copyright (C) 2011-2016 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.gpem17.evo;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.rinde.ecj.BaseEvaluator;
import com.github.rinde.ecj.GPBaseNode;
import com.github.rinde.ecj.GPComputationResult;
import com.github.rinde.ecj.GPProgram;
import com.github.rinde.ecj.GPProgramParser;
import com.github.rinde.evo4mas.common.GpGlobal;
import com.github.rinde.gpem17.GPEM17;
import com.github.rinde.gpem17.GPEM17.ReauctOpt;
import com.github.rinde.gpem17.eval.Evaluate;
import com.github.rinde.gpem17.eval.SimResult;
import com.github.rinde.rinsim.core.model.time.TimeModel;
import com.github.rinde.rinsim.experiment.Experiment.SimulationResult;
import com.github.rinde.rinsim.experiment.ExperimentResults;
import com.github.rinde.rinsim.experiment.MASConfiguration;
import com.github.rinde.rinsim.io.FileProvider;
import com.github.rinde.rinsim.pdptw.common.StatisticsDTO;
import com.github.rinde.rinsim.scenario.Scenario;
import com.github.rinde.rinsim.scenario.StopConditions;
import com.github.rinde.rinsim.scenario.gendreau06.Gendreau06ObjectiveFunction;
import com.google.common.base.Function;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;

import ec.EvolutionState;
import ec.util.Parameter;

/**
 * 
 * @author Rinde van Lon
 */
public class FitnessEvaluator extends BaseEvaluator {

  enum Properties {
    DISTRIBUTED, COMPOSITE_SIZE, NUM_SCENARIOS_PER_GEN, NUM_SCENARIOS_IN_LAST_GEN, REAUCT_OPT, USE_DIFFERENT_SCENARIOS_IN_EVERY_GENERATION, SCENARIOS_REGEX, SCENARIOS_DIR, OBJ_FUNC_WEIGHTS;

    public String toString() {
      return name().toLowerCase();
    }
  }

  // private static final String TRAINSET_PATH = "files/dataset10k";
  static final long MAX_SIM_TIME = 8 * 60 * 60 * 1000L;

  static final Pattern CAPTURE_SCENARIO_NAME_PARTS =
    Pattern.compile(".*(0\\.\\d0)-(\\d+)-(\\d\\.00)-(\\d+?)\\.scen");

  ImmutableList<Path> paths;
  boolean distributed;
  int compositeSize;
  int numScenariosPerGen;
  int numScenariosInLastGen;
  boolean useDifferentScenariosEveryGen;
  ReauctOpt reauctOpt;
  Gendreau06ObjectiveFunction objectiveFunction;
  String scenariosDir;

  public FitnessEvaluator() {}

  @Override
  public void setup(final EvolutionState state, final Parameter base) {
    distributed =
      state.parameters.getBoolean(base.push(Properties.DISTRIBUTED.toString()),
        null, false);
    compositeSize =
      state.parameters.getInt(
        base.push(Properties.COMPOSITE_SIZE.toString()), null);
    numScenariosPerGen =
      state.parameters.getInt(
        base.push(Properties.NUM_SCENARIOS_PER_GEN.toString()), null);
    numScenariosInLastGen =
      state.parameters.getInt(
        base.push(Properties.NUM_SCENARIOS_IN_LAST_GEN.toString()), null);
    useDifferentScenariosEveryGen =
      state.parameters.getBoolean(
        base.push(
          Properties.USE_DIFFERENT_SCENARIOS_IN_EVERY_GENERATION.toString()),
        null, true);

    String objFuncWeights =
      state.parameters.getString(
        base.push(Properties.OBJ_FUNC_WEIGHTS.toString()), null);

    checkArgument(objFuncWeights != null,
      "%s is not set, expected value: 'tt-td-ot'.",
      base.push(Properties.OBJ_FUNC_WEIGHTS.toString()));
    objectiveFunction = GPEM17.parseObjFuncWeights(objFuncWeights);

    String ropt =
      state.parameters.getString(base.push(Properties.REAUCT_OPT.toString()),
        null);
    checkArgument(ropt != null
      && (ropt.equals("EVO") || ropt.equals("CIH")),
      "%s should be 'EVO' or 'CIH', found '%s'.",
      base.push(Properties.REAUCT_OPT.toString()), ropt);
    reauctOpt = ReauctOpt.valueOf(ropt);

    String regex = state.parameters.getString(
      base.push(Properties.SCENARIOS_REGEX.toString()), null);

    scenariosDir = state.parameters.getString(
      base.push(Properties.SCENARIOS_DIR.toString()), null);

    checkArgument(scenariosDir != null,
      "%s is not defined, it should contain the path to a dataset.",
      base.push(Properties.SCENARIOS_DIR.toString()));

    File f = new File(scenariosDir);
    checkArgument(f.exists() && f.isDirectory(),
      "The path '%s' does not exist or is not a directory.", f);

    paths = getScenarioPaths(scenariosDir, regex);
  }

  public static ImmutableList<Path> getScenarioPaths(String dir, String regex) {
    // .*0\\.50-20-1\\.00-.*\\.scen
    List<Path> ps = new ArrayList<>(FileProvider.builder()
      .add(Paths.get(dir))
      .filter("regex:" + regex)
      .build().get().asList());

    checkArgument(!ps.isEmpty(), "The regex '%s' matches no files.", regex);

    // sort on instance id
    Collections.sort(ps, new Comparator<Path>() {
      @Override
      public int compare(Path o1, Path o2) {
        Matcher m1 =
          CAPTURE_SCENARIO_NAME_PARTS.matcher(o1.getFileName().toString());
        Matcher m2 =
          CAPTURE_SCENARIO_NAME_PARTS.matcher(o2.getFileName().toString());
        checkArgument(m1.matches() && m2.matches(), "%s %s", o1, o2);

        double dyn1 = Double.parseDouble(m1.group(1));
        int urg1 = Integer.parseInt(m1.group(2));
        double scale1 = Double.parseDouble(m1.group(3));
        int id1 = Integer.parseInt(m1.group(4));

        double dyn2 = Double.parseDouble(m2.group(1));
        int urg2 = Integer.parseInt(m2.group(2));
        double scale2 = Double.parseDouble(m2.group(3));
        int id2 = Integer.parseInt(m2.group(4));

        return ComparisonChain.start()
          .compare(id1, id2)
          .compare(dyn1, dyn2)
          .compare(urg1, urg2)
          .compare(scale1, scale2)
          .result();
      }
    });
    return ImmutableList.copyOf(ps);
  }

  @Override
  public void evaluatePopulation(EvolutionState state) {
    SetMultimap<GPNodeHolder, IndividualHolder> mapping =
      getGPFitnessMapping(state);
    int fromIndex;
    if (useDifferentScenariosEveryGen) {
      fromIndex = state.generation * numScenariosPerGen;
    } else {
      fromIndex = 0;
    }
    int toIndex;
    int compSize;
    if (state.generation == state.numGenerations - 1) {
      compSize = compositeSize * 5;
      toIndex = fromIndex + numScenariosInLastGen;
    } else {
      compSize = compositeSize;
      toIndex = fromIndex + numScenariosPerGen;
    }
    System.out.println(scenariosDir + " " +
      paths.subList(fromIndex, toIndex).toString().replace(scenariosDir + "/",
        ""));

    String[] args;
    if (distributed) {

      args = new String[] {"--jppf", "--repetitions", "1", "--composite-size",
        Integer.toString(compSize)};
    } else {
      args = new String[] {"--repetitions", "1"};
    }
    File generationDir =
      new File(((StatsLogger) state.statistics).experimentDirectory,
        "generation" + state.generation);

    List<GPProgram<GpGlobal>> programs = new ArrayList<>();
    List<GPNodeHolder> nodes = ImmutableList.copyOf(mapping.keySet());
    for (GPNodeHolder node : nodes) {
      final GPProgram<GpGlobal> prog = GPProgramParser
        .convertToGPProgram((GPBaseNode<GpGlobal>) node.trees[0].child);
      programs.add(prog);
    }

    ExperimentResults results = Evaluate.execute(
      programs,
      false,
      FileProvider.builder().add(paths.subList(fromIndex, toIndex)),
      generationDir,
      false,
      Converter.INSTANCE,
      false,
      reauctOpt,
      objectiveFunction,
      null,
      false,
      false,
      0L,
      args);

    Map<MASConfiguration, GPNodeHolder> configMapping = new LinkedHashMap<>();
    ImmutableList<MASConfiguration> configs =
      results.getConfigurations().asList();

    verify(configs.size() == nodes.size());
    for (int i = 0; i < configs.size(); i++) {
      configMapping.put(configs.get(i), nodes.get(i));
    }

    List<GPComputationResult> convertedResults = new ArrayList<>();
    for (SimulationResult sr : results.getResults()) {
      StatisticsDTO stats =
        ((SimResult) sr.getResultObject()).getStats();
      double cost = objectiveFunction.computeCost(stats);
      float fitness = (float) cost;
      if (!objectiveFunction.isValidResult(stats)) {
        // if the simulation is terminated early, we give a huge penalty, which
        // we reduce based on how far the simulation actually got.
        fitness = Float.MAX_VALUE - stats.simulationTime;
      }
      String id = configMapping.get(sr.getSimArgs().getMasConfig()).string;
      convertedResults.add(SingleResult.create((float) fitness, id, sr));
    }
    processResults(state, mapping, convertedResults);
  }

  @Override
  protected int expectedNumberOfResultsPerGPIndividual(EvolutionState state) {
    if (state.generation == state.numGenerations - 1) {
      return numScenariosInLastGen;
    }
    return numScenariosPerGen;
  }

  public enum Converter implements Function<Scenario, Scenario> {
    INSTANCE {
      @Override
      public Scenario apply(Scenario input) {
        return Scenario.builder(input)
          .removeModelsOfType(TimeModel.AbstractBuilder.class)
          .addModel(TimeModel.builder().withTickLength(250))
          .setStopCondition(StopConditions.or(input.getStopCondition(),
            StopConditions.limitedTime(MAX_SIM_TIME),
            EvoStopCondition.INSTANCE))
          .build();
      }
    }
  }
}
