/*
 * <p>Copyright: Copyright (c) 2006 Sun Microsystems, Inc.</p>
 */

package com.sun.gi.objectstore.tso.dataspace;

import com.sun.gi.objectstore.NonExistantObjectIDException;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * A {@link DataSpace} based on HADB/JDBC.
 */
public class HadbDataSpace implements DataSpace {

    boolean debug = true;
    private long appID;
    private String dataConnURL;

    /*
     * HADB appears to convert all table and column names to
     * lower-case internally, and some of the methods for
     * accessing the metadata do not convert, so it's easier if
     * everything is simply lower-case to begin with.  (otherwise
     * it's possible to successfully create a table with a given
     * name, and then ask whether a table with that name exists,
     * and get the answer "no") -DJE
     */

    private static final String SCHEMAROOT = "simserver";

    private static final String OBJBASETBL = "objects";
    private static final String OBJLOCKBASETBL = "objlocks";
    private static final String NAMEBASETBL = "namedirectory";
    private static final String INFOBASETBL = "appinfo";

    private final String SCHEMA;

    private String NAMETBLNAME;
    private String OBJTBLNAME;
    private String OBJLOCKTBLNAME;
    private String INFOTBLNAME;

    private Connection readConn;
    private Connection updateTransConn;
    private Connection updateSingleConn;
    private Connection idConn;
    private Connection schemaConn;
    
    private PreparedStatement getIdStmnt;
    private PreparedStatement getObjStmnt;
    private PreparedStatement getNameStmnt;
    private PreparedStatement updateObjStmnt;
    private PreparedStatement insertObjStmnt;
    private PreparedStatement updateNameStmnt;
    private PreparedStatement insertNameStmnt;
    private PreparedStatement updateInfoStmnt;
    private PreparedStatement insertInfoStmnt;
    private PreparedStatement updateObjLockStmnt;
    private PreparedStatement updateObjUnlockStmnt;
    private PreparedStatement insertObjLockStmnt;
    private PreparedStatement deleteObjStmnt;
    private PreparedStatement deleteNameStmnt;
    private PreparedStatement clearObjTableStmnt;
    private PreparedStatement clearNameTableStmnt;

    private volatile boolean closed = false;

    private String hadbHosts = null;
    private String hadbUserName = "system";
    private String hadbPassword = "darkstar";
    private String hadbDBname = null;

    /**
     * Creates a DataSpace with the given appId, connected to the
     * "default" HADB instance.
     *
     * @param appId the application ID
     */
    public HadbDataSpace(long appId)
	    throws Exception
    {
	this(appId, false);
    }

    /**
     * Creates a DataSpace with the given appId, connected to the
     * "default" HADB instance.
     *
     * @param appId the application ID
     *
     * @param dropTables if <code>true</code>, then drop all tables
     * before begining.
     */
    public HadbDataSpace(long appID, boolean dropTables)
	    throws Exception
    {
	this.appID = appID;

	// If we can't load the driver, bail out immediately.
	try {
	    Class.forName("com.sun.hadb.jdbc.Driver");
	} catch (Exception e) {
	    System.out.println("ERROR: Failed to load the HADB JDBC driver.");
	    System.out.println(e);
	    throw e;
	}

	if (!loadParams()) {
	    throw new IllegalArgumentException("illegal parameters");
	}

	SCHEMA = SCHEMAROOT + "_" + appID;

	OBJTBLNAME = SCHEMA + "." + OBJBASETBL;
	OBJLOCKTBLNAME = SCHEMA + "." + OBJLOCKBASETBL;
	NAMETBLNAME = SCHEMA + "." + NAMEBASETBL;
	INFOTBLNAME = SCHEMA + "." + INFOBASETBL;

	try {
	    // XXX: MUST take these from a config file.
	    // hadbHosts = "129.148.75.63:15025,129.148.75.60:15005";
	    // String userName = "system";
	    // String userPasswd = "darkstar";

	    String dataConnURL = "jdbc:sun:hadb:" + hadbHosts + ";create=true";

	    System.out.println("XXX\t\tdataConnURL: " + dataConnURL);

	    // XXX:  Do we really need all these connections?

	    updateTransConn = getConnection(dataConnURL,
		    hadbUserName, hadbPassword,
			    Connection.TRANSACTION_READ_COMMITTED);
	    updateSingleConn = getConnection(dataConnURL,
		    hadbUserName, hadbPassword,
			    Connection.TRANSACTION_READ_COMMITTED);
	    updateSingleConn.setAutoCommit(true);

	    idConn = getConnection(dataConnURL, hadbUserName, hadbPassword,
		    Connection.TRANSACTION_READ_COMMITTED);

	    readConn = getConnection(dataConnURL, hadbUserName, hadbPassword,
	 	    Connection.TRANSACTION_READ_COMMITTED);
	    readConn.setReadOnly(true);
	    readConn.setAutoCommit(true);

	    schemaConn = getConnection(dataConnURL, hadbUserName, hadbPassword,
		    Connection.TRANSACTION_REPEATABLE_READ);
	    schemaConn.setAutoCommit(true);

	    if (dropTables) {
		if (!dropTables()) {

		    // This isn't necessarily an error.  The cases
		    // need to be subdivided more carefully.

		    System.out.println("ERROR: Failed to drop all tables.");
		}
	    }

	    checkTables();
	    createPreparedStmnts();

	} catch (Exception e) {
	    e.printStackTrace();
	}
    }


    private boolean loadParams() {
	FileInputStream fis = null;
	Properties hadbParams = new Properties();
	String hadbParamFile = System.getProperty("dataspace.hadbParamFile");

	if (hadbParamFile != null) {
	    try {
		fis = new FileInputStream(hadbParamFile);
	    } catch (IOException e) {
		System.out.println("ERROR: failed to open params file (" +
			hadbParamFile + "): " + e);
		return false;
	    }

	    if (fis != null) {
		try {
		    hadbParams.load(fis);
		} catch (IOException e) {
		    System.out.println("ERROR: failed to read params file (" +
			    hadbParamFile + "): " + e);
		    hadbParams.clear();
		    return false;
		} catch (IllegalArgumentException e) {
		    System.out.println("ERROR: params file (" +
			    hadbParamFile + ") contains errors: " + e);
		    hadbParams.clear();
		    return false;
		}

		try {
		    fis.close();
		} catch (IOException e) {
		    // XXX: Is this really a problem?
		}
	    } 
	}

	hadbHosts = hadbParams.getProperty("dataspace.hadb.hosts",
		System.getProperty("dataspace.hadb.hosts",
			null));
	if (hadbHosts == null) {
	    hadbHosts = "129.148.75.63:15025,129.148.75.60:15005";
	    // hadbHosts = "20.20.10.104:15025,20.20.10.103:15085,20.20.11.107:15105,20.20.10.101:15005,20.20.11.105:15065,20.20.10.102:15045";
	}

	hadbUserName = hadbParams.getProperty("dataspace.hadb.username",
		System.getProperty("dataspace.hadb.username",
			"system"));

	hadbPassword = hadbParams.getProperty("dataspace.hadb.password",
		System.getProperty("dataspace.hadb.password",
			"darkstar"));

	// DO NOT USE: leave hadbDBname null.
	// Setting the database name to anything other than the default
	// doesn't work properly. -DJE

	hadbDBname = hadbParams.getProperty("dataspace.hadb.dbname",
		System.getProperty("dataspace.hadb.dbname",
			null));

	if (debug) {
	    System.out.println("PARAM: dataspace.hadb.hosts: " +
		    hadbHosts);
	    System.out.println("PARAM: dataspace.hadb.username: " +
		    hadbUserName);
	    System.out.println("PARAM: dataspace.hadb.password: " +
		    hadbPassword);
	    System.out.println("PARAM: dataspace.hadb.dbname: " +
		    ((hadbDBname == null) ? "null" : hadbDBname));
	}

	return true;
    }

    private String[] getTableNames() {
	String[] tableNames = {
		OBJTBLNAME, OBJLOCKTBLNAME, NAMETBLNAME, INFOTBLNAME
	};
	return tableNames;
    }

    /**
     * Drops all of the tables.  <p>
     */
    private synchronized boolean dropTables() {

	System.out.println("INFO: Dropping all tables!");

	boolean allSucceeded = true;
	for (String name : getTableNames()) {
	    if (!dropTable(name)) {
		allSucceeded = false;
	    }
	}
	return allSucceeded;
    }

    /**
     * Clears all of the tables <em>except</em> the infotbl.  <p>
     *
     * The infotbl contains the generator for the next object ID. 
     * clearing the infotbl can cause disasters.  (clearing any of the
     * tables when the system is mid-stride can be disasterous, but
     * clearing the infotbl is almost guaranteed to be disasterous).
     */
    public synchronized boolean clearTables() {
	System.out.println("INFO: Clearing tables!");

	boolean allSucceeded = true;
	for (String name : getTableNames()) {
	    // don't clear the INFOTBL.
	    if (!name.equals(INFOTBLNAME) && !clearTable(name)) {
		allSucceeded = false;
	    }
	}

	return allSucceeded;
    }

    private boolean dropTable(String tableName) {
	String s;
	Statement stmnt;

	try {
	    updateTransConn.commit();
	    idConn.commit();
	} catch (Exception e) {
	    System.out.println("ERROR: FAILED to prepare/commit " + tableName);
	    System.out.println("\t" + e);
	    e.printStackTrace();
	}

	stmnt = null;
	s = "DROP TABLE " + tableName;
	try {
	    stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	    System.out.println("INFO: Dropped " + tableName);
	} catch (SQLException e) {
	    System.out.println("ERROR: FAILED to drop " + tableName);
	    System.out.println("\t" + e);
/* 	    e.printStackTrace(); */
	    return false;
	}

	return true;
    }

    private boolean clearTable(String tableName) {
	String s;
	Statement stmnt;

	try {
	    updateTransConn.commit();
	    idConn.commit();
	} catch (Exception e) {
	    System.out.println("ERROR: FAILED to prepare/commit " + tableName);
	    System.out.println("\t" + e);
	    e.printStackTrace();
	}

	stmnt = null;
	s = "DELETE FROM " + tableName;
	try {
	    stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	    System.out.println("INFO: Deleted contents of " + tableName);
	} catch (SQLException e) {
	    System.out.println("ERROR: FAILED to delete " + tableName);
	    System.out.println("\t" + e);
/* 	    e.printStackTrace(); */
	    return false;
	}

	return true;
    }

    /**
     * @throws SQLException
     */
    private synchronized void checkTables() throws SQLException {
	ResultSet rs;
	Statement stmnt = null;
	boolean schemaExists = false;

	/*
	 * It is not possible, in HADB, to bring a schema into
	 * existance simply by defining tables within it.  Therefore
	 * we create it separately -- but this might fail, because the
	 * schema might already exist.  So we need to check whether it
	 * exists first, and then try to create it if it doesn't, etc.
	 *
	 * There's probably a cleaner way.  -DJE
	 */

	try {
	    String s = "select schemaname from sysroot.allschemas" +
		    " where schemaname like '" + SCHEMA + "'";
	    stmnt = schemaConn.createStatement();
	    rs = stmnt.executeQuery(s);
	    if (!rs.next()) {
		System.out.println("INFO: SCHEMA does not exist: " + SCHEMA);
		schemaExists = false;
	    } else {
		System.out.println("INFO: SCHEMA already exists: " + SCHEMA);
		schemaExists = true;
	    }
	    rs.close();
	} catch (SQLException e) {
	    System.out.println("INFO: SCHEMA error: " + e);
	    schemaExists = false;
	}

	if (!schemaExists) {
	    try {
		System.out.println("INFO: Creating Schema");
		String s = "CREATE SCHEMA " + SCHEMA;
		stmnt = schemaConn.createStatement();
		stmnt.execute(s);
	    } catch (SQLException e) {

		/*
		 * 11751 is "this schema already exists"
		 */

		if (e.getErrorCode() != 11751) {
		    System.out.println("ERROR: SCHEMA issue: " + e);
		    throw e;
		} else {
		    System.out.println("INFO: SCHEMA already exists: " + e);
		}
	    }
	}

	HashSet<String> foundTables = new HashSet<String>();

	try {
	    String s = "select tablename from sysroot.alltables" +
		    " where schemaname like '" + SCHEMA + "%'";

	    stmnt = schemaConn.createStatement();
	    rs = stmnt.executeQuery(s);
	    while (rs.next()) {
		foundTables.add(rs.getString(1).trim());
	    }
	    rs.close();
	} catch (SQLException e) {
	    System.out.println("ERROR: failure finding schema" + e);
	    throw e;
	}

	if (foundTables.contains(OBJBASETBL.toLowerCase())) {
	    System.out.println("INFO: Found Objects table");
	} else {
	    createObjTable();
	}

	if (foundTables.contains(OBJLOCKBASETBL.toLowerCase())) {
	    System.out.println("INFO: Found object lock table");
	} else {
	    createObjLockTable();
	}

	if (foundTables.contains(NAMEBASETBL.toLowerCase())) {
	    System.out.println("INFO: Found Name table");
	} else {
	    createNameTable();
	}

	if (foundTables.contains(INFOBASETBL.toLowerCase())) {
	    System.out.println("INFO: Found info table");
	} else {
	    createInfoTable();
	}
    }

    private synchronized void createPreparedStmnts() throws SQLException {

	getObjStmnt = readConn.prepareStatement("SELECT * FROM " +
		OBJTBLNAME + " WHERE OBJID=?");
	getNameStmnt = readConn.prepareStatement("SELECT * FROM " +
		NAMETBLNAME + " WHERE NAME=?");

	deleteNameStmnt = updateTransConn.prepareStatement("DELETE FROM " +
		NAMETBLNAME + " WHERE OBJID=?");

	insertObjStmnt = updateTransConn.prepareStatement("INSERT INTO " +
		OBJTBLNAME + " VALUES(?,?)");
	updateObjStmnt = updateTransConn.prepareStatement("UPDATE " +
		OBJTBLNAME + " SET OBJBYTES=? WHERE OBJID=?");

	insertNameStmnt = updateTransConn.prepareStatement("INSERT INTO " +
		NAMETBLNAME + " VALUES(?,?)");
	updateNameStmnt = updateTransConn.prepareStatement("UPDATE " +
		NAMETBLNAME + " SET NAME=? WHERE OBJID=?");
	deleteObjStmnt = updateTransConn.prepareStatement("DELETE FROM " +
		OBJTBLNAME + " WHERE OBJID=?");

	updateObjLockStmnt = updateSingleConn.prepareStatement("UPDATE " +
		OBJLOCKTBLNAME +
		    " SET OBJLOCK=1 WHERE OBJID=? AND OBJLOCK=0");
	updateObjUnlockStmnt = updateSingleConn.prepareStatement("UPDATE " +
		OBJLOCKTBLNAME +
		    " SET OBJLOCK=0 WHERE OBJID=? AND OBJLOCK=1");
	insertObjLockStmnt = updateSingleConn.prepareStatement("INSERT INTO " +
			OBJLOCKTBLNAME + " VALUES(?,?)");

	// XXX: HADB does not implement table locking.
	// So, we NEED another way to do this.

	clearObjTableStmnt = updateSingleConn.prepareStatement(
		"DELETE FROM " + OBJTBLNAME);
	clearNameTableStmnt = updateSingleConn.prepareStatement(
		"DELETE FROM " + NAMETBLNAME);

	updateInfoStmnt = idConn.prepareStatement("UPDATE " +
		INFOTBLNAME + " SET NEXTIDBLOCKBASE=? " +
		"WHERE APPID=? AND NEXTIDBLOCKBASE=?");

	getIdStmnt = idConn.prepareStatement("SELECT * FROM " +
		INFOTBLNAME + " I  " + "WHERE I.APPID = " + appID);

	idConn.commit();
	updateTransConn.commit();
    }

    private boolean createObjTable() {
	System.out.println("INFO: Creating Objects table");
	Statement stmnt;
	String s = "CREATE TABLE " + OBJTBLNAME + " (" +
		"OBJID DOUBLE INT NOT NULL, " +
		"OBJBYTES BLOB, " +
		"PRIMARY KEY (OBJID))";
	try { 
	    stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	} catch (Exception e) {
	    System.out.println("ERROR: FAILED to create: " + e);
	    return false;
	}

	return true;
    }

    private boolean createInfoTable() {
	System.out.println("INFO: Creating info table");
	Statement stmnt;
	ResultSet rs;

	String s = "CREATE TABLE " + INFOTBLNAME + "(" +
		"APPID DOUBLE INT NOT NULL," +
		"NEXTIDBLOCKBASE DOUBLE INT, " +
		"IDBLOCKSIZE INT, " +
		"PRIMARY KEY(APPID)" + ")";
	try {
	    stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	} catch (SQLException e) {
	    // XXX
	    System.out.println("ERROR: FAILED to create: " + e);
	    return false;
	}

	s = "SELECT * FROM " + INFOTBLNAME + " I  " +
		"WHERE I.APPID = " + appID;
	try {
	    stmnt = schemaConn.createStatement();
	    rs = stmnt.executeQuery(s);
	    if (!rs.next()) { // entry does not exist
		System.out.println("INFO: Creating new entry in info table for appID "
		    + appID);
		stmnt = schemaConn.createStatement();
		s = "INSERT INTO " + INFOTBLNAME + " VALUES(" +
			appID + "," + defaultIdStart + "," +
			defaultIdBlockSize + ")";
		stmnt.executeUpdate(s);

		/*
		 * If the table is being re-created, make sure that
		 * the cached state for the table is invalidated as
		 * well.
		 */

		currentIdBlockBase	= DataSpace.INVALID_ID;
		// System.out.println("invalidating currentIdBlockBase: " + currentIdBlockBase);
	    }
	    rs.close();
	} catch (SQLException e) {
	    System.out.println("ERROR: FAILED to create: " + e);
	    return false;
	    // XXX: ??
	}

	return true;
    }

    private boolean createObjLockTable() {
	System.out.println("INFO: Creating object Lock table");
	String s = "CREATE TABLE " + OBJLOCKTBLNAME + " (" +
		"OBJID DOUBLE INT NOT NULL," +
		"OBJLOCK INT NOT NULL," +
		"PRIMARY KEY (OBJID)" + ")";
	try {
	    Statement stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	} catch (SQLException e) {
	    // XXX
	    System.out.println("ERROR: FAILED to create: " + e);
	    return false;
	}
	return true;
    }

    private boolean createNameTable() {
	System.out.println("INFO: Creating Name table");
	String s = "CREATE TABLE " + NAMETBLNAME + "(" +
		"NAME VARCHAR(255) NOT NULL, " +
		"OBJID DOUBLE INT NOT NULL," +
		"PRIMARY KEY (NAME)" + ")";
	try {
	    Statement stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	} catch (SQLException e) {
	    // XXX
	    System.out.println("ERROR: FAILED to create: " + e);
	    return false;
	}

	s = "CREATE INDEX " + NAMETBLNAME + "_r ON " + NAMETBLNAME + "(OBJID)";
	try {
	    Statement stmnt = schemaConn.createStatement();
	    stmnt.execute(s);
	} catch (SQLException e) {
	    // XXX
	    System.out.println("ERROR: FAILED to create: " + e);
	    e.printStackTrace();
	    return false;
	}

	return true;
    }

    /**
     * @return
     */
    private Connection getConnection(String dataConnURL,
	    String userName, String userPasswd, int isolation)
    {
	try {
	    Connection conn = DriverManager.getConnection(dataConnURL,
		    userName, userPasswd);
	    conn.setAutoCommit(false);
	    conn.setTransactionIsolation(isolation);
	    return conn;
	} catch (Exception e) {
	    System.out.println(e);
	    e.printStackTrace();
	}
	return null;
    }

    /*
     * Internal parameters for object ID generation:
     *
     * defaultIdStart is where the object ID numbers start.  It is
     * only used to initialize the database and should never be used
     * directly.  (only IDs taken from the database are guaranteed
     * valid)
     *
     * defaultIdBlockSize is the number of object IDs grabbed at one
     * time.  This is currently very small, for debugging.  Should be
     * at least 1000 for real use.
     */

    private final long defaultIdStart		= 10;
    private final long defaultIdBlockSize	= 10;

    /*
     * Internal variables for object ID generation:
     *
     * currentIdBlockBase is the start of the current block of OIDs
     * that this instance of an HADB can use to generate OIDs.  It
     * begins with an illegal value to force a fetch from the
     * database.
     *
     * currentIdBlockOffset is the offset of the next OID to generate
     * within the current block.
     */

    private long currentIdBlockBase	= DataSpace.INVALID_ID;
    private long currentIdBlockOffset	= 0;

    /**
     * {@inheritDoc}
     */
    private synchronized long getNextID() {

	/*
	 * In order to minimize the overhead of creating new object
	 * IDs, we allocate them in blocks rather than individually. 
	 * If there are still remaining object Ids available in the
	 * current block owned by this reference to the DataSpace,
	 * then the next such Id is allocated and returned. 
	 * Otherwise, a new block is accessed from the database.
	 */

	// System.out.println("currentIdBlockBase = " +  currentIdBlockBase);

	if ((currentIdBlockBase == DataSpace.INVALID_ID) ||
		(currentIdBlockOffset >= defaultIdBlockSize)) {
	    long newIdBlockBase = 0;
	    int newIdBlockSize = 0;
	    boolean success = false;

	    // System.out.println("going to database for new blockID " + currentIdBlockBase);
	    try {
		// DJE: idConn.commit();
	    } catch (Exception e) {
		System.out.println("UNEXPECTED EXCEPTION: " + e);
	    }

	    try {
		long backoffSleep = 0;
		while (!success) {

		    /*
		     * Get the current value of the NEXTIDBLOCKBASE
		     * from the database.  It is a serious problem if
		     * this fails -- it might not be possible to
		     * recover from this condition.
		     */

		    ResultSet rs = getIdStmnt.executeQuery();
		    if (!rs.next()) {
			System.out.println("appID table entry absent for " +
				appID);
			// XXX: FATAL unexpected error.
		    }
		    newIdBlockBase = rs.getLong("NEXTIDBLOCKBASE"); // "2"
		    newIdBlockSize = rs.getInt("IDBLOCKSIZE"); // "3"

		    /*
		    System.out.println("NEXTIDBLOCKBASE/IDBLOCKSIZE " +
			    newIdBlockBase + "/" + newIdBlockSize);
		    */

		    int rc;

		    updateInfoStmnt.setLong(1, newIdBlockBase + newIdBlockSize);
		    updateInfoStmnt.setLong(2, appID);
		    updateInfoStmnt.setLong(3, newIdBlockBase);
		    try {
			rc = updateInfoStmnt.executeUpdate();
			if (rc == 1) {
			    updateInfoStmnt.getConnection().commit();
			    success = true;
			} else {
			    updateInfoStmnt.getConnection().rollback();
			    success = false;
			}
		    } catch (SQLException e) {
			if (e.getErrorCode() == 2097) {
/* 			    System.out.println("CONTENTION THROW "); */
			    success = false;
			    updateInfoStmnt.getConnection().rollback();
			    try {
				Thread.sleep(2);
			    } catch (Exception e2) {
			    }
			} else {
			    System.out.println("YY " + e + " " + e.getErrorCode());
			    e.printStackTrace();
			}
		    } finally {
			// System.out.println("\t\tClosing...");
			rs.close();
		    }

		    /*
		     * If we didn't succeed, then try again, perhaps
		     * after a short pause.  The pause backs off to a 
		     * maximum of 5ms.
		     */
		    if (!success) {
			if (backoffSleep > 0) {
			    try {
				Thread.sleep(backoffSleep);
			    } catch (InterruptedException e) {
			    }
			}
			backoffSleep++;
			if (backoffSleep > 5) {
			    backoffSleep = 5;
			}
		    }
		}
	    } catch (SQLException e) {
		// XXX
		System.out.println("TT " + e + " " + e.getErrorCode());
		e.printStackTrace();
	    }

	    currentIdBlockBase = newIdBlockBase;
	    currentIdBlockOffset = 0;
	}

	long newOID = currentIdBlockBase + currentIdBlockOffset++;
	// System.out.println("NEW OID " + newOID);

	/*
	 * For the sake of convenience, create the object lock
	 * immediately, instead of waiting for the atomic update to
	 * occur.  This streamlines the update (and allows us to lock
	 * objects that exist in the world but don't exist in the
	 * objtable).
	 */

	try {
	    // System.out.println("inserting oid " + newOID);
	    insertObjLockStmnt.setLong(1, newOID);
	    insertObjLockStmnt.setInt(2, 0);
	} catch (SQLException e) {
	    // XXX: ??
	}

	try {
	    insertObjLockStmnt.executeUpdate();
	    insertObjLockStmnt.getConnection().commit();
	    // System.out.println("SUCCESS for OID: " + newOID);
	} catch (SQLException e) {
	    try {
		insertObjLockStmnt.getConnection().rollback();
	    } catch (SQLException e2) {
		System.out.println("FAILED TO ROLLBACK");
		System.out.println(e2);
	    }
	    System.out.println("FAILURE for OID: " + newOID);
	    System.out.println(e);
	    e.printStackTrace();
	}

	return newOID;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized byte[] getObjBytes(long objectID) {
	byte[] objbytes = null;
	try {
	    getObjStmnt.setLong(1, objectID);
	    ResultSet rs = getObjStmnt.executeQuery();
	    // getObjStmnt.getConnection().commit();
	    if (rs.next()) {
		// objbytes = rs.getBytes("OBJBYTES");
		Blob b = rs.getBlob("OBJBYTES");
		objbytes = b.getBytes(1L, (int) b.length());
	    }
	    rs.close(); // cleanup and free locks
	} catch (SQLException e) {
	    System.out.println(e);
	    e.printStackTrace();
	}
	return objbytes;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void lock(long objectID)
	    throws NonExistantObjectIDException
    {

	/*
	 * This is ugly.  I'd love to hear about a better approach.
	 *
	 * Locks are stored in a table in the database, with one row
	 * per object.  To get a lock on an object, an attempt is made
	 * to update the row for that object with a new value for the
	 * locked column of that row.  If the update succeeds, then
	 * this update acquires the lock.  If the update fails, then
	 * the process is repeated until it succeeds or the caller
	 * gives up in some manner (not part of the interface).
	 *
	 * At present the lock table is a different table than the
	 * object storage, but it could be squeezed into that table as
	 * well.  This may be an item to tune later.
	 */

	 int rc = -1;
	 long backoffSleep = 0;

	 for (;;) {

	    /*
	     * Is it necessary to reset the values of the parameters
	     * each time, or do they survive execution?  If the latter,
	     * then the "set"s can be hoisted out of the loop. -DJE
	     */

	    rc = 0;
	    try {
		updateObjLockStmnt.setLong(1, objectID);
		rc = updateObjLockStmnt.executeUpdate();
		if (rc == 1) {
		    // updateObjLockStmnt.getConnection().commit();
		} else {
		    // updateObjLockStmnt.getConnection().rollback();
		}
	    } catch (SQLException e) {
		try {
		    updateObjLockStmnt.getConnection().rollback();
		} catch (SQLException e2) {
		    System.out.println("FAILED TO ROLLBACK");
		    System.out.println(e2);
		}
		System.out.println("Blocked on " + objectID);
		System.out.println(e);
		e.printStackTrace();
	    }

	    if (rc == 1) {
		/*
		System.out.println("Got the lock on " +
			objectID + " rc = " + rc);
		*/
		return;
	    } else {
		System.out.println("Missed the lock on " +
			objectID + " rc = " + rc);

		/*
		 * If we didn't succeed, then try again, perhaps after
		 * a short pause.  The pause backs off to a maximum of
		 * 5ms.
		 */

		if (backoffSleep > 0) {
		    try {
			Thread.sleep(backoffSleep);
		    } catch (InterruptedException e) {
		    }
		}
		backoffSleep++;
		if (backoffSleep > 5) {
		    backoffSleep = 5;
		}
	    }
	}
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void release(long objectID) {
	
	/*
	 * Similar to lock, except it doesn't poll.  If the update
	 * fails, it assumes that's because the lock is already
	 * unlocked, and rolls back.
	 *
	 * There might be a race condition here:  can't tell without
	 * looking at the actual packets, which is bad.  -DJE
	 */

	int rc = -1;
	try {
	    updateObjUnlockStmnt.setLong(1, objectID);
	} catch (SQLException e) {
	    System.out.println("FAILED to set parameters");
	}

	try {
	    rc = updateObjUnlockStmnt.executeUpdate();
	    if (rc == 1) {
		// updateObjUnlockStmnt.getConnection().commit();
	    } else {
		// updateObjUnlockStmnt.getConnection().rollback();
	    }
	} catch (SQLException e) {
	    /*
	    try {
		// updateObjUnlockStmnt.getConnection().rollback();
	    } catch (SQLException e2) {
		System.out.println("FAILED TO ROLLBACK");
		System.out.println(e2);
	    }
	    */
	    System.out.println("Tried to unlock (" + objectID +
		    "): already unlocked.");
	    System.out.println(e);
	    e.printStackTrace();
	}

	if (rc == 1) {
	    // System.out.println("Released the lock on " + objectID + " rc = " + rc);
	    return;
	} else {
	    System.out.println("Didn't need to unlock " + objectID + " rc = " + rc);
	    // XXX: not an error.  This is a diagnostic only.
	}
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void atomicUpdate(boolean clear,
	    Map<Long, byte[]> updateMap)
	throws DataSpaceClosedException
    {
	Map<String, Long> dummyNewNames = new HashMap<String, Long>();
	Set<Long> dummyIdSet = new HashSet<Long>();

	atomicUpdate(clear, dummyNewNames, dummyIdSet, updateMap, dummyIdSet);
    }

    /**
     * {@inheritDoc}
     */
    private synchronized void atomicUpdate(boolean clear,
	    Map<String, Long> newNames,
	    Set<Long> deleteSet, Map<Long, byte[]> updateMap,
	    Set<Long> insertIDs)
	throws DataSpaceClosedException
    {
	if (closed) {
	    throw new DataSpaceClosedException();
	}

	/*
	 * Debugging:  commit any outstanding transactions.  There
	 * SHOULDN'T be any, but if there's a bug elsewhere, keep it
	 * out of this transaction.

	try {
	    updateObjStmnt.getConnection().commit();
	} catch (SQLException e) {
	    // XXX: rollback and die
	    // Is there anything we can do here, other than weep?
	    e.printStackTrace();
	}
	 */

	if (newNames.entrySet().size() > 0) {
	    for (Entry<String, Long> e : newNames.entrySet()) {
		long oid = e.getValue();
		String name = e.getKey();

		/*
		 * If something is in the deleteSet, then there's no need
		 * to insert it because we're going to remove it as part
		 * of this atomic op.
		 */

		if (deleteSet.contains(oid)) {
		    continue;
		}

		try {
		    insertNameStmnt.setString(1, name);
		    insertNameStmnt.setLong(2, oid);
		    insertNameStmnt.addBatch();
		} catch (SQLException e1) {
		    // XXX: rollback and die
		    e1.printStackTrace();
		}

	    }

	    try {
		insertNameStmnt.executeBatch();
	    } catch (SQLException e1) {
		// XXX: rollback and die
		e1.printStackTrace();
	    }
	}

	if (updateMap.entrySet().size() > 0) {
	    for (Entry<Long, byte[]> e : updateMap.entrySet()) {
		long oid = e.getKey();
		byte[] data = e.getValue();

		if (deleteSet.contains(oid)) {
		    continue;
		}

		try {
		    if (insertIDs.contains(oid)) {
			insertObjStmnt.setLong(1, oid);
			insertObjStmnt.setBytes(2, data);
			insertObjStmnt.addBatch();
		    } else {
			updateObjStmnt.setBytes(1, data);
			updateObjStmnt.setLong(2, oid);
			updateObjStmnt.addBatch();
		    }
		} catch (SQLException e1) {
		    // XXX: rollback and die
		    e1.printStackTrace();
		}
	    }

	    if (insertIDs.size() > 0) {
		try {
		    insertObjStmnt.executeBatch();
		} catch (SQLException e1) {
		    // XXX: rollback and die
		    e1.printStackTrace();
		}
	    }

	    if ((updateMap.entrySet().size() - insertIDs.size()) > 0) {
		try {
		    updateObjStmnt.executeBatch();
		} catch (SQLException e1) {
		    // XXX: rollback and die
		    e1.printStackTrace();
		}
	    }
	}

	for (long oid : deleteSet) {
	    if (insertIDs.contains(oid)) {
		continue;
	    }

	    try {
		deleteObjStmnt.setLong(1, oid);
		deleteObjStmnt.execute();
	    } catch (SQLException e1) {
		// XXX: rollback and die
		e1.printStackTrace();
	    }
	}

	// There's got to be a better way.
	try {
	    updateTransConn.commit();
	    // deleteObjStmnt.getConnection().commit();
	    // updateObjStmnt.getConnection().commit();
	    // insertNameStmnt.getConnection().commit();
	} catch (SQLException e) {
	    // XXX: rollback and die
	    // Is there anything we can do here, other than weep?
	    e.printStackTrace();
	}

    }

    /**
     * {inheritDoc}
     */
    public synchronized Long lookup(String name) {
	long oid = DataSpace.INVALID_ID;

	try {
	    getNameStmnt.setString(1, name);
	    ResultSet rs = getNameStmnt.executeQuery();
	    // getNameStmnt.getConnection().commit();
	    if (rs.next()) {
		oid = rs.getLong("OBJID");
	    }
	    rs.close();
	} catch (SQLException e) {
	    e.printStackTrace();
	}
	return oid;
    }

    /*
     * {@inheritDoc}
     */
    public synchronized long getAppID() {
	return appID;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void clear() {

	try {
	    checkTables();
	    clearTables();
	} catch (SQLException e) {
	    e.printStackTrace();
	}
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close() {

	try {
	    idConn.commit();
	    idConn.close();
	} catch (SQLException e) {
	    System.out.println("can't close idConn " + e);
	}

	try {
	    updateTransConn.commit();
	    updateTransConn.close();
	} catch (SQLException e) {
	    System.out.println("can't close updateTransConn " + e);
	}

	try {
	    updateSingleConn.close();
	} catch (SQLException e) {
	    System.out.println("can't close updateSingleConn " + e);
	}

	try {
	    schemaConn.close();
	} catch (SQLException e) {
	    System.out.println("can't close schemaConn " + e);
	}

	try {
	    readConn.close();
	} catch (SQLException e) {
	    System.out.println("can't close readConn " + e);
	}

	System.out.println("CLOSED");

	closed = true;
    }

    /*
     * {@inheritDoc}
     * @see com.sun.gi.objectstore.tso.dataspace.DataSpace#newName(java.lang.String)
     */
    public boolean newName(String name) {
	return false;
    }

    public void destroy(long objectID) throws NonExistantObjectIDException {

	// XXX: write me.

	// XXX: doesn't check for bad objectIDs

	try {
	    deleteObjStmnt.setLong(1, objectID);
	    deleteObjStmnt.executeUpdate();

	} catch (SQLException e) {
	    try {
		deleteObjStmnt.getConnection().rollback();
	    } catch (SQLException e2) {
		// XXX: DO SOMETHING.
	    }
	    
	    // XXX: need to double-check that this is WHY
	    // we got into this state.

	    throw new NonExistantObjectIDException("object " + objectID +
		    " does not exist");
	}

	try {
	    deleteNameStmnt.setLong(1, objectID);
	    deleteNameStmnt.executeUpdate();

	    deleteObjStmnt.getConnection().commit();
	} catch (SQLException e) {
	    try {
		deleteObjStmnt.getConnection().rollback();
	    } catch (SQLException e2) {
		// XXX: DO SOMETHING.
	    }
	}

	return;
    }

    public long create(byte[] data, String name) {
	// XXX: write me.

	if (data == null) {
	    throw new NullPointerException("data is null");
	}

	long oid = getNextID();

	if (name != null) {
	    try {
		insertNameStmnt.setString(1, name);
		insertNameStmnt.setLong(2, oid);
		insertNameStmnt.executeUpdate();
	    } catch (SQLException e) {
		System.out.println("create: " + e);
		// Was there already an identical name?
		// Do we need to commit to find out?
	    }
	}

	try {
	    insertObjStmnt.setLong(1, oid);
	    insertObjStmnt.setBytes(2, data);
	    insertObjStmnt.executeUpdate();

	    insertObjStmnt.getConnection().commit();

	    return oid;
	} catch (SQLException e) {
	    try {
		insertObjStmnt.getConnection().rollback();
	    } catch (SQLException e2) {
		// XXX: DO SOMETHING.
	    }
	    return DataSpace.INVALID_ID;
	}
    }
}
