package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanCommandTest {

    @Test
    fun `read services are allowed`() {
        assertNull(CanCommand.rejectionReason("22 B0 02"))
        assertNull(CanCommand.rejectionReason("21 01"))
        assertNull(CanCommand.rejectionReason("01 0D"))
        assertNull(CanCommand.rejectionReason("0902"))
    }

    /**
     * Головна причина існування цієї перевірки: сервіси запису можуть змінити
     * налаштування блоків авто, і діагностичний застосунок їх не надсилає.
     */
    @Test
    fun `write services are refused`() {
        assertNotNull(CanCommand.rejectionReason("2E F1 90")) // запис за ідентифікатором
        assertNotNull(CanCommand.rejectionReason("31 01"))    // запуск процедури
        assertNotNull(CanCommand.rejectionReason("2F 00"))    // керування виходом
        assertNotNull(CanCommand.rejectionReason("11 01"))    // скидання блока
        assertNotNull(CanCommand.rejectionReason("14"))       // очищення помилок
    }

    @Test
    fun `garbage is refused`() {
        assertNotNull(CanCommand.rejectionReason(""))
        assertNotNull(CanCommand.rejectionReason("   "))
        assertNotNull(CanCommand.rejectionReason("ZZ 01"))
        assertNotNull(CanCommand.rejectionReason("2"))
    }

    @Test
    fun `lower case and extra spaces are accepted`() {
        assertNull(CanCommand.rejectionReason("  22   b0 02 "))
        assertEquals("22 B0 02", CanCommand.normalize("  22   b0 02 "))
    }

    @Test
    fun `headers are eleven or twenty nine bit`() {
        assertTrue(CanCommand.isValidHeader("7C6"))
        assertTrue(CanCommand.isValidHeader("7e4"))
        assertTrue(CanCommand.isValidHeader("18DAF110"))
        assertFalse(CanCommand.isValidHeader("7C"))
        assertFalse(CanCommand.isValidHeader("7C67"))
        assertFalse(CanCommand.isValidHeader("ZZZ"))
    }
}
