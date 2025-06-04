package com.sap.cloud.sample.mail;

import java.io.IOException;
import java.io.PrintWriter;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.sap.cloud.sample.mail.session.MailSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet implementing a mail example which shows how to use the connectivity service APIs to send e-mail.
 * The example provides a simple UI to compose an e-mail message and send it. The post method uses
 * the connectivity service and the javax.mail API to send the e-mail.
 */
public class MailServlet extends HttpServlet {

    public static final String FROM_ADDRESS = "fromaddress";
    public static final String TO_ADDRESS = "toaddress";
    public static final String LOCALHOST = "localhost";
    public static final String SUBJECT_TEXT = "subjecttext";
    public static final String MAIL_TEXT = "mailtext";
    public static final String SENT_RESPONSE = "E-mail was sent (in local scenario stored in '<local-server>/work/mailservice')";

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String TEXT_HTML = "text/html";
    private static final String UTF_8 = "UTF-8";
    private static final String PLAIN = "plain";
    private static final String ALTERNATIVE = "alternative";

    private static final String NAME = "Session";
//    @Resource(name = "mail/Session")
    private Session mailSession;

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(MailServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        mailSession = getSession();
        // Show input form to user
        response.setHeader(CONTENT_TYPE, TEXT_HTML);
        PrintWriter writer = response.getWriter();
        writer.write("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" "
                + "\"http://www.w3.org/TR/html4/loose.dtd\">");
        writer.write("<html><head><title>Mail Test</title></head><body>");
        writer.write("<form action='' method='post'>");
        writer.write("<table style='width: 100%'>");
        writer.write("<tr>");
        writer.write("<td width='100px'><label>From:</label></td>");
        writer.write(String.format("<td><input type='text' size='50' value='' name='%s'></td>", FROM_ADDRESS));
        writer.write("</tr>");
        writer.write("<tr>");
        writer.write("<td><label>To:</label></td>");
        writer.write(String.format("<td><input type='text' size='50' value='' name='%s'></td>", TO_ADDRESS));
        writer.write("</tr>");
        writer.write("<tr>");
        writer.write("<td><label>Subject:</label></td>");
        writer.write(String.format("<td><textarea rows='1' cols='100' name='%s'>Subject</textarea></td>", SUBJECT_TEXT));
        writer.write("</tr>");
        writer.write("<tr>");
        writer.write("<td><label>Mail:</label></td>");
        writer.write(String.format("<td><textarea rows='7' cols='100' name='%s'>Mail Text</textarea></td>", MAIL_TEXT));
        writer.write("</tr>");
        writer.write("<tr>");
        writer.write("<tr>");
        writer.write("<td><input type='submit' value='Send Mail'></td>");
        writer.write("</tr>");
        writer.write("</table>");
        writer.write("</form>");
        writer.write("</body></html>");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        mailSession = getSession();
        Transport transport = null;
        try {
            // Parse form parameters
            String from = request.getParameter(FROM_ADDRESS);
            String to = request.getParameter(TO_ADDRESS);
            String subjectText = request.getParameter(SUBJECT_TEXT);
            String mailText = request.getParameter(MAIL_TEXT);
            if (from.isEmpty() || to.isEmpty()) {
                throw new RuntimeException("Form parameters From and To may not be empty!");
            }

            // Construct message from parameters
            MimeMessage mimeMessage = new MimeMessage(mailSession);
            InternetAddress[] fromAddress = InternetAddress.parse(from);
            InternetAddress[] toAddresses = InternetAddress.parse(to);
            mimeMessage.setFrom(fromAddress[0]);
            mimeMessage.setRecipients(RecipientType.TO, toAddresses);
            mimeMessage.setSubject(subjectText, UTF_8);
            MimeMultipart multiPart = new MimeMultipart(ALTERNATIVE);
            MimeBodyPart part = new MimeBodyPart();
            part.setText(mailText, UTF_8, PLAIN);
            multiPart.addBodyPart(part);
            mimeMessage.setContent(multiPart);
            // Send mail
            transport = mailSession.getTransport();
            transport.connect();
            transport.sendMessage(mimeMessage, mimeMessage.getAllRecipients());

            // Confirm mail sending
            response.getWriter().println(SENT_RESPONSE);
        } catch (Exception e) {
            LOGGER.error("Mail operation failed", e);
            throw new ServletException(e);
        } finally {
            // Close transport layer
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException e) {
                    throw new ServletException(e);
                }
            }
        }
    }

    private static Session getSession() throws IOException {
        try {
            return new MailSession().getSession(NAME);
        } catch (NoSuchProviderException e) {
            throw new IOException("No Such Provider Exception: ",e);
        }
    }
}
