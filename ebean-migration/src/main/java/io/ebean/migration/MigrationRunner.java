package io.ebean.migration;

import io.ebean.migration.runner.LocalMigrationResource;
import io.ebean.migration.runner.LocalMigrationResources;
import io.ebean.migration.runner.MigrationPlatform;
import io.ebean.migration.runner.MigrationSchema;
import io.ebean.migration.runner.MigrationTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
/**
 * Runs the DB migration typically on application start.
 */
public class MigrationRunner {

  private static final Logger logger = LoggerFactory.getLogger(MigrationRunner.class);

  private final MigrationConfig migrationConfig;

  private final List<LocalMigrationResource> checkMigrations;

  public MigrationRunner(MigrationConfig migrationConfig) {
    this.migrationConfig = migrationConfig;
    this.checkMigrations = new ArrayList<>();
  }

  /**
   * Return the migrations that would be applied if the migration is invoke.
   */
  public List<LocalMigrationResource> checkState() {
    return checkState(getMigrationConfig().createConnection());
  }

  /**
   * Return the migrations that would be applied if the migration is invoke.
   */
  public List<LocalMigrationResource> checkState(DataSource dataSource) {
    return checkState(getConnection(dataSource));
  }

  /**
   * Return the migrations that would be applied if the migration is invoke.
   */
  public List<LocalMigrationResource> checkState(Connection connection) {
    invoke(connection, true);
    return getCheckMigrations();
  }

  /**
   * Run by creating a DB connection from driver, url, username defined in getMigrationConfig().
   */
  public void run() {
    run(getMigrationConfig().createConnection());
  }

  /**
   * Run using the connection from the DataSource.
   */
  public void run(DataSource dataSource) {
    run(getConnection(dataSource));
  }

  /**
   * Run the migrations if there are any that need running.
   */
  public void run(Connection connection) {
    invoke(connection, false);
  }

  public MigrationConfig getMigrationConfig() {

    return this.migrationConfig;
  }

  public List<LocalMigrationResource> getCheckMigrations() {

    return this.checkMigrations;
  }

  private Connection getConnection(DataSource dataSource) {
    String username = getMigrationConfig().getDbUsername();
    try {
      if (username == null) {
        return dataSource.getConnection();
      }
      logger.debug("using db user [{}] to invoke migrations ...", username);
      return dataSource.getConnection(username, getMigrationConfig().getDbPassword());
    } catch (SQLException e) {
      String msgSuffix = (username == null) ? "" : " using user [" + username + "]";
      throw new IllegalArgumentException("Error trying to connect to database for DB Migration" + msgSuffix, e);
    }
  }

  /**
   * Run the migrations if there are any that need running.
   */
  protected void invoke(Connection connection, boolean checkStateMode) {

    LocalMigrationResources resources = new LocalMigrationResources(getMigrationConfig());
    if (!resources.readResources()) {
      logger.debug("no migrations to check");
      return;
    }

    try {
      connection.setAutoCommit(false);
      MigrationPlatform platform = derivePlatformName(connection);

      new MigrationSchema(getMigrationConfig(), connection).createAndSetIfNeeded();

      MigrationTable table = new MigrationTable(getMigrationConfig(), connection, checkStateMode, platform);
      table.createIfNeededAndLock();

      runMigrations(resources, table, checkStateMode);
      connection.commit();

      table.runNonTransactional();

    } catch (MigrationException e) {
      rollback(connection);
      throw e;

    } catch (Exception e) {
      rollback(connection);
      throw new RuntimeException(e);

    } finally {
      close(connection);
    }
  }

  /**
   * Run all the migrations as needed.
   */
  private void runMigrations(LocalMigrationResources resources, MigrationTable table, boolean checkStateMode) throws SQLException {

    // get the migrations in version order
    List<LocalMigrationResource> localVersions = resources.getVersions();

    if (table.isEmpty()) {
      LocalMigrationResource initVersion = getInitVersion();
      if (initVersion != null) {
        // invoke using a dbinit script
        logger.info("dbinit migration version:{}  local migrations:{}  checkState:{}", initVersion, localVersions.size(), checkStateMode);
        getCheckMigrations().clear();
        getCheckMigrations().addAll(table.runInit(initVersion, localVersions));
        return;
      }
    }

    logger.info("local migrations:{}  existing migrations:{}  checkState:{}", localVersions.size(), table.size(), checkStateMode);
    getCheckMigrations().clear();
    getCheckMigrations().addAll(table.runAll(localVersions));
  }

  /**
   * Return the last init migration.
   */
  private LocalMigrationResource getInitVersion() {
    LocalMigrationResources initResources = new LocalMigrationResources(getMigrationConfig());
    if (initResources.readInitResources()) {
      List<LocalMigrationResource> initVersions = initResources.getVersions();
      if (!initVersions.isEmpty()) {
        return initVersions.get(initVersions.size() - 1);
      }
    }
    return null;
  }

  /**
   * Return the platform deriving from connection if required.
   */
  private MigrationPlatform derivePlatformName(Connection connection) {

    String platformName = getMigrationConfig().getPlatformName();
    if (platformName == null) {
      platformName = DbNameUtil.normalise(connection);
      getMigrationConfig().setPlatformName(platformName);
    }

    return DbNameUtil.platform(platformName);
  }

  /**
   * Close the connection logging if an error occurs.
   */
  private void close(Connection connection) {
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (SQLException e) {
      logger.warn("Error closing connection", e);
    }
  }

  /**
   * Rollback the connection logging if an error occurs.
   */
  private void rollback(Connection connection) {
    try {
      if (connection != null) {
        connection.rollback();
      }
    } catch (SQLException e) {
      logger.warn("Error on connection rollback", e);
    }
  }
}
