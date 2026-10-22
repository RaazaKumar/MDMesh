/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.service;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class EmailServiceTest {

    private static EmailService service() {
        return new EmailService("smtp.example.com", 587, false, true, "", "", "mdm", "pw", "mdm@example.com",
                "", "", "", "", "", "", "", "");
    }

    @Test
    public void aSilentMailServerTimesOutInsteadOfBlockingForever() {
        Properties p = service().smtpProperties();
        assertEquals(String.valueOf(EmailService.CONNECT_TIMEOUT_MS), p.getProperty("mail.smtp.connectiontimeout"));
        assertEquals(String.valueOf(EmailService.READ_TIMEOUT_MS), p.getProperty("mail.smtp.timeout"));
        assertEquals(String.valueOf(EmailService.WRITE_TIMEOUT_MS), p.getProperty("mail.smtp.writetimeout"));
    }

    @Test
    public void keepsTheExistingConnectionSettings() {
        Properties p = service().smtpProperties();
        assertEquals("smtp.example.com", p.get("mail.smtp.host"));
        assertEquals(587, p.get("mail.smtp.port"));
        assertEquals(true, p.get("mail.smtp.starttls.enable"));
        assertEquals(true, p.get("mail.smtp.auth"));
    }

    @Test
    public void aSingleAddressIsParsedStrictly() {
        assertEquals("a@example.com", EmailService.singleAddress(" a@example.com ").getAddress());
        assertEquals("a@example.com", EmailService.singleAddress("Ann <a@example.com>").getAddress());
        assertNull(EmailService.singleAddress("a@example.com, b@example.com"));
        assertNull(EmailService.singleAddress("a@example.com,b@example.com"));
        assertNull(EmailService.singleAddress("a@example.com\r\nBcc: c@example.com"));
        assertNull(EmailService.singleAddress("not-an-address"));
        assertNull(EmailService.singleAddress(""));
        assertNull(EmailService.singleAddress(null));
    }

    @Test
    public void aPlainTextMailToAnAddressListIsRefused() {
        // Refused before any connection is tried, so this needs no mail server.
        assertFalse(service().sendPlainTextEmail("a@example.com, b@example.com", "s", "b"));
    }
}
