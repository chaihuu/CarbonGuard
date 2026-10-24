/*
 * CarbonGuard
 * Copyright (C) 2026 chaihuu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 chaihuu
 * SPDX-License-Identifier: GPL-3.0-only
 */

package xyz.chaihu.carbonguard.mail;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;

/**
 * Small Jakarta Mail sender. The port is never hard-coded: 465 selects implicit SSL,
 * every other port selects STARTTLS (587 is the conventional choice). The password is
 * an authorisation code for QQ Mail, never the mailbox login password.
 */
public final class MailService implements MailSender, AutoCloseable {
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final Logger logger;
    private final ExecutorService executor;
    private volatile Boolean healthy = Boolean.FALSE;

    public MailService(Supplier<CarbonGuardConfig> configSupplier, Logger logger) {
        this.configSupplier = configSupplier;
        this.logger = logger;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "carbonguard-mail-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(MailService.class.getClassLoader());
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(2, factory);
    }

    @Override
    public boolean available() {
        return configSupplier.get().smtp().configured();
    }

    /** True only after a successful SMTP connect or send with the current config. */
    public boolean operational() {
        return configSupplier.get().smtp().configured() && Boolean.TRUE.equals(healthy);
    }

    /**
     * Verifies SMTP connectivity/authentication without sending a message. This is
     * what lets the binding gate distinguish "configured" from "actually usable".
     */
    public CompletableFuture<Boolean> probe() {
        CarbonGuardConfig.Smtp smtp = configSupplier.get().smtp();
        if (!smtp.configured()) {
            healthy = Boolean.FALSE;
            return CompletableFuture.completedFuture(false);
        }
        healthy = null;
        return CompletableFuture.supplyAsync(() -> {
            try {
                Properties properties = smtpProperties(smtp);
                boolean auth = smtp.username() != null && !smtp.username().isBlank();
                Session session;
                if (auth) {
                    session = Session.getInstance(properties, new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(smtp.username(), smtp.password());
                        }
                    });
                } else {
                    session = Session.getInstance(properties);
                }
                try (Transport transport = session.getTransport("smtp")) {
                    if (auth) {
                        transport.connect(smtp.host(), smtp.port(), smtp.username(), smtp.password());
                    } else {
                        transport.connect();
                    }
                }
                healthy = Boolean.TRUE;
                logger.info("SMTP 健康检查通过 (" + smtp.host() + ":" + smtp.port() + ")");
                return true;
            } catch (Exception e) {
                healthy = Boolean.FALSE;
                logger.log(Level.WARNING, "SMTP 健康检查失败 (" + smtp.host() + ":" + smtp.port()
                        + ")，未绑定拦截将保持关闭", e);
                return false;
            }
        }, executor);
    }

    private static Properties smtpProperties(CarbonGuardConfig.Smtp smtp) {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", smtp.host());
        properties.setProperty("mail.smtp.port", Integer.toString(smtp.port()));
        properties.setProperty("mail.smtp.connectiontimeout", Integer.toString(smtp.connectionTimeoutMillis()));
        properties.setProperty("mail.smtp.timeout", Integer.toString(smtp.connectionTimeoutMillis()));
        properties.setProperty("mail.smtp.writetimeout", Integer.toString(smtp.connectionTimeoutMillis()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.quitwait", "false");
        boolean implicitTls = smtp.useImplicitTls();
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(!implicitTls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(!implicitTls));
        properties.setProperty("mail.smtp.auth",
                Boolean.toString(smtp.username() != null && !smtp.username().isBlank()));
        return properties;
    }

    @Override
    public CompletableFuture<Void> sendVerification(String email, String playerName, String code, int ttlSeconds) {
        CarbonGuardConfig.Smtp smtp = configSupplier.get().smtp();
        if (!smtp.configured()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SMTP 未配置"));
        }
        return CompletableFuture.runAsync(() -> send(smtp, email, playerName, code, ttlSeconds), executor);
    }

    private void send(CarbonGuardConfig.Smtp smtp, String email, String playerName, String code, int ttlSeconds) {
        try {
            Properties properties = smtpProperties(smtp);
            boolean auth = smtp.username() != null && !smtp.username().isBlank();

            Session session;
            if (auth) {
                session = Session.getInstance(properties, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(smtp.username(), smtp.password());
                    }
                });
            } else {
                session = Session.getInstance(properties);
            }
            session.setDebug(false);

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtp.from(), "CarbonGuard 验证中心", StandardCharsets.UTF_8.name()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email, true));
            message.setSubject("CarbonGuard 邮箱绑定验证码", StandardCharsets.UTF_8.name());
            message.setSentDate(new Date());
            message.setHeader("X-CarbonGuard-Purpose", "email-binding");

            String safeName = escapeHtml(playerName);
            String minutes = Math.max(1, ttlSeconds / 60) + " 分钟";
            String plain = "你好 " + playerName + "：\n\n"
                    + "你的 CarbonGuard 邮箱绑定验证码是：" + code + "\n"
                    + "验证码 " + minutes + " 内有效，且只能使用一次。\n"
                    + "若非本人操作，请忽略本邮件。\n";
            String html = "<!doctype html><html><body style=\"font-family:sans-serif;color:#1f2937\">"
                    + "<p>你好 <b>" + safeName + "</b>：</p>"
                    + "<p>你的 CarbonGuard 邮箱绑定验证码是：</p>"
                    + "<p style=\"font-size:30px;letter-spacing:8px;font-weight:bold\">" + code + "</p>"
                    + "<p>验证码 " + minutes + " 内有效，且只能使用一次。</p>"
                    + "<p style=\"color:#6b7280\">若非本人操作，请忽略本邮件。</p>"
                    + "</body></html>";

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(plain, StandardCharsets.UTF_8.name());
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=UTF-8");
            MimeMultipart multipart = new MimeMultipart("alternative");
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(htmlPart);
            message.setContent(multipart);

            Transport.send(message);
            healthy = Boolean.TRUE;
            logger.info("验证码邮件已发送至 " + maskEmail(email));
        } catch (MessagingException e) {
            healthy = Boolean.FALSE;
            logger.log(Level.WARNING, "SMTP 发送失败 (" + smtp.host() + ":" + smtp.port() + ")", e);
            throw new IllegalStateException("SMTP 发送失败", e);
        } catch (Exception e) {
            healthy = Boolean.FALSE;
            logger.log(Level.WARNING, "构造验证码邮件失败", e);
            throw new IllegalStateException("邮件构造失败", e);
        }
    }

    public static String maskEmail(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String masked = local.length() <= 2 ? "*".repeat(local.length())
                : local.charAt(0) + "*".repeat(Math.min(5, local.length() - 2)) + local.charAt(local.length() - 1);
        return masked + email.substring(at);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
