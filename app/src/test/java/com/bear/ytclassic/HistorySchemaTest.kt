package com.bear.ytclassic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.0.1 crash: the history table's primary key was uploaded_ms instead of url. */
class HistorySchemaTest {
    @Test fun historyIsKeyedByUrl() {
        val ddl = Db.historyDdl("history")
        assertTrue(ddl, ddl.contains("PRIMARY KEY(url)"))
        assertFalse(ddl, ddl.contains("uploaded_ms INTEGER PRIMARY KEY"))
    }
}
