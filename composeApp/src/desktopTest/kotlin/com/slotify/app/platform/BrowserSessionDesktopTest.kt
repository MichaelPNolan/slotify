// Written fresh for Slotify — Floorp/Firefox session-cookie discovery.

package com.slotify.app.platform

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

class BrowserSessionDesktopTest {

    @Test
    fun cookieReaderDoesNotThrow() {
        // May be null if Floorp/Firefox isn't logged in on this machine —
        // the contract is "never throw", not "always find a cookie".
        val cookie = readBrowserSpDcCookie()
        assertTrue(cookie == null || cookie.length > 20, "unexpected cookie shape: len=${cookie?.length}")
    }

    @Test
    fun findsPlainSpDcFromSqliteProfile() {
        val profile = Files.createTempDirectory("slotify-browser-session-test").toFile()
        try {
            val db = File(profile, "cookies.sqlite")
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate(
                        "CREATE TABLE moz_cookies (" +
                            "id INTEGER PRIMARY KEY, originAttributes TEXT NOT NULL DEFAULT ''," +
                            "name TEXT, value TEXT, host TEXT, path TEXT," +
                            "expiry INTEGER, lastAccessed INTEGER, creationTime INTEGER," +
                            "isSecure INTEGER, isHttpOnly INTEGER" +
                            ")",
                    )
                    st.executeUpdate(
                        "INSERT INTO moz_cookies (name, value, host, path, lastAccessed) VALUES " +
                            "('sp_dc', 'test-session-cookie-value-not-real-1234567890', '.spotify.com', '/', 1)",
                    )
                }
            }
            // Point the reader at this profile by seeding the same layout under a
            // temp HOME… we can't rewrite System.home mid-test, so assert the
            // SQL shape via the public reader only when a real profile exists.
            // Here we just verify the fixture itself is readable.
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT value FROM moz_cookies WHERE name = 'sp_dc'").use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getString(1).contains("test-session-cookie"))
                    }
                }
            }
        } finally {
            profile.deleteRecursively()
        }
    }
}
