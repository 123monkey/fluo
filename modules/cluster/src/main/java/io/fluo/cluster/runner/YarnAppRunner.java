/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.cluster.runner;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Service;
import io.fluo.accumulo.util.ZookeeperPath;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.cluster.main.FluoOracleMain;
import io.fluo.cluster.main.FluoWorkerMain;
import io.fluo.cluster.yarn.FluoTwillApp;
import io.fluo.cluster.yarn.TwillUtil;
import io.fluo.core.client.FluoAdminImpl;
import io.fluo.core.util.CuratorUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillPreparer;
import org.apache.twill.api.TwillRunResources;
import org.apache.twill.api.TwillRunnerService;
import org.apache.twill.internal.RunIds;
import org.apache.twill.yarn.YarnTwillRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yarn Implementation of ClusterAppRunner
 */
public class YarnAppRunner extends ClusterAppRunner implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(YarnAppRunner.class);
  private TwillRunnerService twillRunner;
  private CuratorFramework curator;
  private String hadoopPrefix;

  public YarnAppRunner(String fluoHomeDir, FluoConfiguration config, String hadoopPrefix) {
    super("fluo", fluoHomeDir, config);
    this.hadoopPrefix = hadoopPrefix;
  }

  public YarnAppRunner(String fluoHomeDir, String appName, String hadoopPrefix) {
    super("fluo", fluoHomeDir, appName);
    this.hadoopPrefix = hadoopPrefix;
  }

  private synchronized CuratorFramework getCurator() {
    if (curator == null) {
      curator = CuratorUtil.newAppCurator(config);
      curator.start();
    }
    return curator;
  }

  private synchronized TwillRunnerService getTwillRunner() {
    if (twillRunner == null) {
      YarnConfiguration yarnConfig = new YarnConfiguration();
      yarnConfig.addResource(new Path(hadoopPrefix + "/etc/hadoop/core-site.xml"));
      yarnConfig.addResource(new Path(hadoopPrefix + "/etc/hadoop/yarn-site.xml"));

      twillRunner = new YarnTwillRunnerService(yarnConfig, config.getAppZookeepers() + ZookeeperPath.TWILL);
      twillRunner.startAndWait();

      // sleep to give twill time to retrieve state from zookeeper
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
    return twillRunner;
  }

  private void checkIfInitialized() {
    try (FluoAdminImpl admin = new FluoAdminImpl(config)) {
      if (!admin.zookeeperInitialized()) {
        System.out.println(
            "ERROR - A Fluo '" + config.getApplicationName() + "' application has not been initialized yet in Zookeeper at " + config.getAppZookeepers());
        System.exit(-1);
      }
    }
  }

  @Override
  public void start() {
    checkIfInitialized();
    TwillPreparer preparer = null;

    if (twillIdExists()) {
      String runId = getTwillId();

      TwillController controller = getTwillRunner().lookup(config.getApplicationName(), RunIds.fromString(runId));
      if ((controller != null) && controller.isRunning()) {
        System.err.println("WARNING - A YARN application " + getAppInfo() + " is already running for the Fluo '" + config.getApplicationName()
            + "' application!  Please stop it using 'fluo stop " + config.getApplicationName() + "' before starting a new one.");
        System.exit(-1);
      } else {
        logExistsButNotRunning();
        System.exit(-1);
      }
    }

    if (!config.hasRequiredOracleProps() || !config.hasRequiredWorkerProps()) {
      System.err.println("Failed to start Fluo '" + config.getApplicationName() + "' application because fluo.properties is missing required properties.");
      System.exit(-1);
    }

    try {
      config.validate();
    } catch (IllegalArgumentException e) {
      System.err.println("Error - Invalid fluo.properties due to "+ e.getMessage());
      System.exit(-1);
    } catch (Exception e) {
      System.err.println("Error - Invalid fluo.properties due to "+ e.getMessage());
      e.printStackTrace();
      System.exit(-1);
    }

    preparer = getTwillRunner().prepare(new FluoTwillApp(config, getAppConfDir()));

    // Add jars from fluo lib/ directory that are not being loaded by Twill.
    try {
      File libDir = new File(getLibDir());
      for (File f : libDir.listFiles()) {
        if (f.isFile()) {
          String jarPath = "file:" + f.getCanonicalPath();
          log.trace("Adding library jar (" + f.getName() + ") to Fluo application.");
          preparer.withResources(new URI(jarPath));
        }
      }

      // Add jars found in application's lib dir
      File observerDir = new File(getAppLibDir());
      for (File f : observerDir.listFiles()) {
        String jarPath = "file:" + f.getCanonicalPath();
        log.debug("Adding application jar (" + f.getName() + ") to Fluo application.");
        preparer.withResources(new URI(jarPath));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    Preconditions.checkNotNull(preparer, "Failed to prepare twill application");
    TwillController controller = preparer.start();

    log.info("Starting Fluo '{}' application in YARN...", config.getApplicationName());
    controller.start();

    try {
      // set twill run id zookeeper
      String twillId = controller.getRunId().toString();
      CuratorUtil.putData(getCurator(), ZookeeperPath.YARN_TWILL_ID, twillId.getBytes(StandardCharsets.UTF_8), CuratorUtil.NodeExistsPolicy.FAIL);

      while (controller.isRunning() == false) {
        Thread.sleep(500);
      }
      // set app id in zookeeper
      String appId = controller.getResourceReport().getApplicationId();
      CuratorUtil.putData(getCurator(), ZookeeperPath.YARN_APP_ID, appId.getBytes(StandardCharsets.UTF_8), CuratorUtil.NodeExistsPolicy.FAIL);

      log.info("The Fluo '{}' application is running in YARN {}", config.getApplicationName(), getAppInfo());

      log.info("Waiting for all desired containers to start...");
      int checks = 0;
      while (allContainersRunning(controller) == false) {
        Thread.sleep(500);
        checks++;
        if (checks == 30) {
          log.warn("Still waiting... YARN may not have enough resources available for this application.  Use ctrl-c to stop waiting and check status using 'fluo yarn info'.");
        }
      }
      log.info("All desired containers are running in YARN " + getAppInfo());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void stop() {
    checkIfInitialized();
    String twillId = verifyTwillId();

    TwillController controller = getTwillRunner().lookup(config.getApplicationName(), RunIds.fromString(twillId));
    if (controller != null) {
      System.out.print("Stopping Fluo '" + config.getApplicationName() + "' application "+ getAppInfo() + "...");
      controller.stopAndWait();
      System.out.println("DONE");
    } else {
      logExistsButNotRunning();
    }
    deleteZkData();
  }

  public void kill() throws Exception {
    checkIfInitialized();
    String twillId = verifyTwillId();

    TwillController controller = getTwillRunner().lookup(config.getApplicationName(), RunIds.fromString(twillId));
    if (controller != null) {
      System.out.print("Killing Fluo '" + config.getApplicationName() + "' application "+ getAppInfo() + "...");
      controller.kill();
      System.out.println("DONE");
    } else {
      logExistsButNotRunning();
    }
    deleteZkData();
  }

  private boolean allContainersRunning(TwillController controller) {
    return TwillUtil.numRunning(controller, FluoOracleMain.ORACLE_NAME) == config.getOracleInstances()
        && TwillUtil.numRunning(controller, FluoWorkerMain.WORKER_NAME) == config.getWorkerInstances();
  }

  private String containerStatus(TwillController controller) {
    return "" + TwillUtil.numRunning(controller, FluoOracleMain.ORACLE_NAME) + " of " + config.getOracleInstances() +
        " Oracle containers and " + TwillUtil.numRunning(controller, FluoWorkerMain.WORKER_NAME) + " of " + config.getWorkerInstances() + " Worker containers";
  }

  public void status(boolean extraInfo) {
    checkIfInitialized();
    if (twillIdExists() == false) {
      System.out.print("A Fluo '" + config.getApplicationName() + "' application is not running in YARN.");
      return;
    }
    String twillId = getTwillId();
    TwillController controller = getTwillRunner().lookup(config.getApplicationName(), RunIds.fromString(twillId));
    if (controller == null) {
      logExistsButNotRunning();
      System.err.println("You can clean up this reference by running 'fluo yarn stop' or 'fluo yarn kill'.");
    } else {
      Service.State state = controller.state();
      System.out.println("A Fluo '" + config.getApplicationName() + "' application is " + state + " in YARN " + getFullInfo());

      if (state.equals(Service.State.RUNNING) && (allContainersRunning(controller) == false)) {
        System.out.println("\nWARNING - The Fluo application is not running all desired containers!  YARN may not have enough available resources.  Application is currently running "
            + containerStatus(controller));
      }

      if (extraInfo) {
        Collection<TwillRunResources> resources = controller.getResourceReport().getRunnableResources(FluoOracleMain.ORACLE_NAME);
        System.out.println("\nThe application has " + resources.size() + " of " + config.getOracleInstances() + " desired Oracle containers:\n");
        TwillUtil.printResources(resources);

        resources = controller.getResourceReport().getRunnableResources(FluoWorkerMain.WORKER_NAME);
        System.out.println("\nThe application has " + resources.size() + " of " + config.getWorkerInstances() + " desired Worker containers:\n");
        TwillUtil.printResources(resources);
      }
    }
  }

  private String verifyTwillId() {
    if (twillIdExists() == false) {
      System.err.println("WARNING - A YARN application is not referenced in Zookeeper for this Fluo application.  Check if there is a Fluo application "
          + "running in YARN using the command 'yarn application -list`. If so, verify that your fluo.properties is configured correctly.");
      System.exit(-1);
    }
    return getTwillId();
  }

  private void logExistsButNotRunning() {
    System.err.println("WARNING - A Fluo '" + config.getApplicationName() + "' application is not running in YARN but it is " + getAppInfo() + " is referenced in Zookeeper");
  }

  private String getAppInfo()  {
    return "(yarn id = " + getAppId() + ")";
  }

  private String getFullInfo() {
    return "(yarn id = " + getAppId() + ", twill id = "+ getTwillId() + ")";
  }

  private boolean twillIdExists() {
    try {
      return getCurator().checkExists().forPath(ZookeeperPath.YARN_TWILL_ID) != null;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String getTwillId() {
    try {
      return new String(getCurator().getData().forPath(ZookeeperPath.YARN_TWILL_ID), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void deleteZkData() {
    try {
      getCurator().delete().forPath(ZookeeperPath.YARN_TWILL_ID);
      getCurator().delete().forPath(ZookeeperPath.YARN_APP_ID);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String getAppId() {
    try {
      return new String(getCurator().getData().forPath(ZookeeperPath.YARN_APP_ID), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() throws Exception {
    if (twillRunner != null) {
      twillRunner.stop();
    }
    if (curator != null) {
      curator.close();
    }
  }
}