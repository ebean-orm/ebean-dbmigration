package io.ebean.migration;

import io.ebean.migration.runner.LocalMigrationResource;
import org.avaje.datasource.DataSourceConfig;
import org.avaje.datasource.DataSourcePool;
import org.avaje.datasource.Factory;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MigrationRunnerTest {

  private MigrationConfig createMigrationConfig() {
    MigrationConfig config = new MigrationConfig();
    config.setDbUsername("sa");
    config.setDbPassword("");
    config.setDbDriver("org.h2.Driver");
    config.setDbUrl("jdbc:h2:mem:db1");

    return config;
  }

  @Test
  public void run_when_createConnection() {

    MigrationConfig config = createMigrationConfig();

    config.setMigrationPath("dbmig");
    MigrationRunner runner = new MigrationRunner(config);

    List<LocalMigrationResource> check = runner.checkState();
    assertThat(check).hasSize(4);

    runner.run();
  }

  @Test
  public void run_when_fileSystemResources() {

    MigrationConfig config = createMigrationConfig();

    config.setMigrationPath("filesystem:test-fs-resources/fsdbmig");
    MigrationRunner runner = new MigrationRunner(config);
    runner.run();
  }

  @Test
  public void run_when_suppliedConnection() {

    MigrationConfig config = createMigrationConfig();
    Connection connection = config.createConnection();

    config.setMigrationPath("dbmig");
    MigrationRunner runner = new MigrationRunner(config);
    runner.run(connection);
  }

  @Test
  public void run_when_suppliedDataSource() {

    DataSourceConfig dataSourceConfig = new DataSourceConfig();
    dataSourceConfig.setDriver("org.h2.Driver");
    dataSourceConfig.setUrl("jdbc:h2:mem:tests");
    dataSourceConfig.setUsername("sa");
    dataSourceConfig.setPassword("");

    Factory factory = new Factory();
    DataSourcePool dataSource = factory.createPool("test", dataSourceConfig);

    MigrationConfig config = createMigrationConfig();
    config.setMigrationPath("dbmig");

    MigrationRunner runner = new MigrationRunner(config);
    runner.run(dataSource);
    System.out.println("-- run second time --");
    runner.run(dataSource);

    // simulate change to repeatable migration
    config.setMigrationPath("dbmig3");
    System.out.println("-- run third time --");
    runner.run(dataSource);

    config.setMigrationPath("dbmig4");

    config.setPatchResetChecksumOn("m2_view,1.2");
    List<LocalMigrationResource> checkState = runner.checkState(dataSource);
    assertThat(checkState).hasSize(1);
    assertThat(checkState.get(0).getVersion().asString()).isEqualTo("1.3");

    config.setPatchInsertOn("1.3");
    checkState = runner.checkState(dataSource);
    assertThat(checkState).isEmpty();

    System.out.println("-- run forth time --");
    runner.run(dataSource);

    System.out.println("-- run fifth time --");
    checkState = runner.checkState(dataSource);
    assertThat(checkState).isEmpty();
    runner.run(dataSource);

  }

  /**
   * Run this integration test manually against CockroachDB.
   */
  @Test(enabled = false)
  public void cockroach_integrationTest() {

    MigrationConfig config = createMigrationConfig();
    config.setDbUsername("unit");
    config.setDbPassword("unit");
    config.setDbDriver("org.postgresql.Driver");
    config.setDbUrl("jdbc:postgresql://127.0.0.1:26257/unit");
    config.setMigrationPath("dbmig-roach");


    MigrationRunner runner = new MigrationRunner(config);
    runner.run();
  }
}