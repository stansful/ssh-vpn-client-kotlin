package com.stansful.sshvpnclient.data.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SqliteQueryBatchesTest {
    @Test
    fun `large collections stay below the SQLite argument limit`() {
        val values = (0 until 10_000).map(Int::toString)

        val batches = values.sqliteQueryBatches()

        assertEquals(values, batches.flatten())
        assertTrue(batches.all { batch -> batch.size <= SQLITE_QUERY_BATCH_SIZE })
        assertEquals(12, batches.size)
    }

    @Test
    fun `empty collections do not produce an empty SQL batch`() {
        assertTrue(emptyList<String>().sqliteQueryBatches().isEmpty())
    }

    @Test
    fun `invalid batch size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            listOf("profile").sqliteQueryBatches(batchSize = 0)
        }
    }
}
