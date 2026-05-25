package io.zefio.gateway.http;

import io.zefio.core.Ingress;
import io.zefio.core.Upstream;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ExchangePattern;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.builder.config.*;
import io.zefio.testsupport.endpoint.UpstreamToIngressIntegrationTestCase;
import io.zefio.testsupport.payload.FixedPayloadBuilderFactory;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Http Multipart upload test")
public class HttpMultipartTestCase extends UpstreamToIngressIntegrationTestCase {

    public HttpMultipartTestCase() throws Exception {
        super("httpInbound_multipart", "httpOutbound_multipart");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        // 1. Set Framing strategy
        // Since the message itself is a single unit in JMS, the EOF (no splitting) type is mainly used.
        FramingField framing = new FramingField();
        framing.setType(FramingType.EOF);

        // 🚀 Since HTTP Multipart body parsing is impossible,
        // Utilize SpEL's 'Static String' feature to assign a fixed TrxID.
        // 💡 Caution on expression: It is in the format of "#{'String'}" with single quotes inside double quotes.
        CorrelationField correlation = new CorrelationField(CorrelationIdType.SpEL);
        correlation.setExpression("#{'MULTIPART-TEST-TRX-ID'}");

        // 3. Create Telegram and builder (utilize JsonValues @Builder)
        FixedValues.FixedField rawField = new FixedValues.FixedField();
        rawField.setName("RAW_BODY");
        rawField.setLength(0); // By convention, 0 means variable/entire remainder
        rawField.setTrim(false); // It is recommended to explicitly set trim to false as Multipart data should not be trimmed

        PayloadBuilder builder = new Telegram.Builder()
                .name("http-multipart-raw")
                .type(Telegram.Type.Fixed)
                .values(FixedValues.builder()
                        .framing(framing)
                        .correlation(correlation)
                        // 🚀 To add a layout, you can capture the whole as a single field like this.
                        .layout(Collections.singletonList(rawField))
                        .encodingIgnore(true)
                        .build())
                .build();

        return new FixedPayloadBuilderFactory(builder, senderEncoding);
    }

    @Override
    public Ingress createReceiver(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new HttpIngress(context);
    }

    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new HttpUpstream(context);
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        // Simulation of returning the uploaded file name as a response from the server side
        String responseMessage = "{\"result\":\"OK\",\"filename\":\"upload_test.txt\"}";
        requestPayload.setBody(responseMessage.getBytes(requestPayload.getCurrentEncoding()));

        return requestPayload;
    }

    @Test
    @DisplayName("Http Multipart file upload transmission/reception test")
    void testHttpMultipartUpload() throws Exception {
        // Create a test file to upload
//        Paths.get("src", "test", "resources", dirName).toAbsolutePath()
        File file = new File("src/test/resources/upload/multipart_1byte.log");
        assertTrue(file.exists(), "The test file must exist.");

        // Read file as byte[]
        byte[] fileBytes = readFileToBytes(file);

        // Construct body in multipart format (simply including boundary)
        String boundary = "----BoundaryTest1234";
        String multipartBody =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n" +
                        "Content-Type: text/plain\r\n\r\n" +
                        new String(fileBytes, senderEncoding) + "\r\n" +
                        "--" + boundary + "--\r\n";

        Payload requestPayload = senderBuilder.withBody(multipartBody.getBytes(senderEncoding), senderEncoding);

        // Execute actual transmission/reception
        sender.executeAsync(requestPayload, Executors.newSingleThreadExecutor()).join();

        // Event received by Inbound
        Payload responsePayload = getReceiverCapturedEvent();

        assertNotNull(responsePayload);
        assertNotNull(responsePayload.getBody());

        String actualResponse = new String(responsePayload.getBody(), responsePayload.getCurrentEncoding());
        System.out.println("Response Body = " + actualResponse);

        assertTrue(actualResponse.contains("OK"));
    }

    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }
}
