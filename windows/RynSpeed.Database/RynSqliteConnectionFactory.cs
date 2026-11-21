using Microsoft.Data.Sqlite;
using RynSpeed.Security;

namespace RynSpeed.Database;

/// <summary>
/// Opens encrypted SQLCipher connections with WAL mode and strict foreign key enforcement.
/// </summary>
public sealed class RynSqliteConnectionFactory(string dbFilePath, DpapiSecretStore secretStore)
{
    private static bool _initialized;
    private static readonly object InitLock = new();

    public SqliteConnection CreateOpenConnection()
    {
        EnsureSqlCipherInitialized();
        var dir = Path.GetDirectoryName(dbFilePath);
        if (!string.IsNullOrWhiteSpace(dir))
            Directory.CreateDirectory(dir);

        var passphraseHex = secretStore.GetOrCreateDatabasePassphraseHex();
        var connStr = new SqliteConnectionStringBuilder
        {
            DataSource = dbFilePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Password = passphraseHex
        }.ToString();

        var connection = new SqliteConnection(connStr);
        connection.Open();

        using var pragma = connection.CreateCommand();
        pragma.CommandText = """
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            PRAGMA busy_timeout = 5000;
            """;
        pragma.ExecuteNonQuery();
        return connection;
    }

    public void ApplySchemaScript(string schemaSql)
    {
        using var conn = CreateOpenConnection();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = schemaSql;
        cmd.ExecuteNonQuery();
    }

    private static void EnsureSqlCipherInitialized()
    {
        if (_initialized) return;
        lock (InitLock)
        {
            if (_initialized) return;
            SQLitePCL.Batteries_V2.Init();
            _initialized = true;
        }
    }
}
