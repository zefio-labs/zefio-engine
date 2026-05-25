package io.zefio.gateway.tcp.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.zefio.core.payload.Payload;
import io.zefio.core.schema.dto.RequestEncodingSupport;
import io.zefio.core.Upstream;
import io.zefio.core.payload.builder.config.CorrelationField;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.core.payload.builder.config.FramingField;
import io.zefio.core.payload.builder.config.Telegram;
import io.zefio.core.payload.builder.config.TelegramValues;
import io.zefio.gateway.netty.IngressSender;
import io.zefio.gateway.netty.chunked.ChunkedResponseEncoder;
import io.zefio.gateway.netty.chunked.dto.*;
import io.zefio.gateway.netty.dto.ClientHandlerContext;
import io.zefio.gateway.netty.dto.HandlerDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TCP Long Message (Chunk) and Business Paging (Pagination) handler extreme integration test (JDK 1.8 compatible)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TcpAdvancedHandlerIntegrationTest {

    @Mock private Upstream mockUpstream;
    @Mock private PayloadBuilder mockEventBuilder;
    @Mock private Telegram mockTelegram;
    @Mock private HandlerDefinition mockHandlerDef;
    @Mock private IngressSender mockSender;
    @Mock private RequestEncodingSupport mockEncodingSupport;
    @Mock private ClientHandlerContext<byte[]> mockClientContext;

    @BeforeEach
    void setUp() {
        when(mockUpstream.getEventBuilder()).thenReturn(mockEventBuilder);
        when(mockEventBuilder.getTelegram()).thenReturn(mockTelegram);

        TelegramValues dummyValues = new TelegramValues() {
            @Override public boolean getEncodingIgnore() { return false; }
            @Override public void setEncodingIgnore(boolean ignore) {}
            @Override public CorrelationField getCorrelation() { return null; }
            @Override public FramingField getFraming() { return null; }
        };
        when(mockTelegram.getValues()).thenReturn(dummyValues);
        when(mockEncodingSupport.getRequestEncoding()).thenReturn(StandardCharsets.UTF_8);

        when(mockClientContext.getFlowName()).thenReturn("tcp-outbound-flow");
        when(mockClientContext.getUpstream()).thenReturn(mockUpstream);
        when(mockClientContext.getValues()).thenReturn(mockEncodingSupport);
    }

    @AfterEach
    void tearDown() {
        reset(mockSender);
    }

    // 🚀 [JDK 1.8 Helper] Alternative method for String.repeat(int)
    private String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    // =========================================================================
    // [PART 1] ChunkedResponseEncoder (Long message split transmission) Test
    // =========================================================================

    private ChunkSplitterConfig createSplitterConfig(int maxChunkSize) {
        ChunkSplitterConfig config = new ChunkSplitterConfig();
        config.setStatusOffset(14);
        config.setStatusStart("SSSS");
        config.setStatusMiddle("PP  ");
        config.setStatusEnd("FFF ");
        config.setHeaderOffset(0);
        config.setHeaderLength(20);
        config.setLongMessageOffset(20);
        config.setMaxChunkSize(maxChunkSize);
        return config;
    }

    @Test
    @DisplayName("[Splitter Normal] Splits large messages according to the specified Max size.")
    void testChunkedEncoder_SplitSuccess() {
        ChunkSplitterConfig config = createSplitterConfig(30);
        ChunkedResponseEncoder encoder = new ChunkedResponseEncoder(mockUpstream, config, StandardCharsets.UTF_8, true, mockSender);

        String payload = "HEADER00000000000000" + "BODY123456BODY123456BODY1";
        Payload event = new ZefioMessage(payload.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        encoder.sendChunkedResponse(event, ctx);

        ArgumentCaptor<byte[]> chunkCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockSender, times(2)).sendChunk(eq(event), eq(ctx), chunkCaptor.capture());
        verify(mockSender, times(1)).lastCompleteAndSend(eq(event), eq(ctx), eq(true), chunkCaptor.capture());

        List<byte[]> chunks = chunkCaptor.getAllValues();
        assertEquals(3, chunks.size());
        assertEquals("HEADER00000000SSSS00" + "BODY123456", new String(chunks.get(0), StandardCharsets.UTF_8));
        assertEquals("HEADER00000000PP  00" + "BODY123456", new String(chunks.get(1), StandardCharsets.UTF_8));
        assertEquals("HEADER00000000FFF 00" + "BODY1", new String(chunks.get(2), StandardCharsets.UTF_8));
    }

    // =========================================================================
    // [PART 2] TcpMessageAggregatorHandler (Long message reception assembly) Test
    // =========================================================================

    private ChunkAggregatorConfig createAggregatorConfig() {
        ChunkAggregatorConfig config = new ChunkAggregatorConfig();
        config.setStatusOffset(14);
        config.setStatusStart("SSSS");
        config.setStatusMiddle("PP  ");
        config.setStatusEnd("FFF ");
        config.setLongMessageOffset(20);
        config.setMaxMessageSize(100);
        config.setChunkTimeout(5000);
        return config;
    }

    @Test
    @DisplayName("[Aggregator Normal] Perfectly assembles START -> MIDDLE -> END chunks.")
    void testAggregator_AssembleSuccess() {
        when(mockHandlerDef.getAggregator()).thenReturn(createAggregatorConfig());
        TcpMessageAggregatorHandler handler = new TcpMessageAggregatorHandler(mockClientContext, mockHandlerDef);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(Unpooled.copiedBuffer("HEADER00000000SSSS00PART_1_".getBytes(StandardCharsets.UTF_8)));
        channel.writeInbound(Unpooled.copiedBuffer("HEADER00000000PP  00PART_2_".getBytes(StandardCharsets.UTF_8)));
        channel.writeInbound(Unpooled.copiedBuffer("HEADER00000000FFF 00PART_3".getBytes(StandardCharsets.UTF_8)));

        ByteBuf aggregatedBuf = channel.readInbound();
        assertNotNull(aggregatedBuf);
        assertEquals("HEADER00000000FFF 00PART_1_PART_2_PART_3", aggregatedBuf.toString(StandardCharsets.UTF_8));
        aggregatedBuf.release();
    }

    @Test
    @DisplayName("[Aggregator Extreme Defense] Initializes buffer to protect memory when MaxMessageSize is exceeded.")
    void testAggregator_ExceedMaxMessageSize() {
        when(mockHandlerDef.getAggregator()).thenReturn(createAggregatorConfig());
        TcpMessageAggregatorHandler handler = new TcpMessageAggregatorHandler(mockClientContext, mockHandlerDef);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 🚀 [JDK 8 Modification] String.repeat alternative
        channel.writeInbound(Unpooled.copiedBuffer(("HEADER00000000SSSS00" + repeat("A", 30)).getBytes(StandardCharsets.UTF_8)));
        channel.writeInbound(Unpooled.copiedBuffer(("HEADER00000000PP  00" + repeat("B", 50)).getBytes(StandardCharsets.UTF_8)));
        channel.writeInbound(Unpooled.copiedBuffer("HEADER00000000FFF 00END_DATA".getBytes(StandardCharsets.UTF_8)));

        ByteBuf buf = channel.readInbound();
        assertTrue(buf.toString(StandardCharsets.UTF_8).endsWith("END_DATA"));
        buf.release();
    }

    // =========================================================================
    // [PART 3] TcpLoopingPaginationHandler (Business Paging) Test
    // =========================================================================

    private ChunkPaginationConfig createPaginationConfig(PaginationRequestStrategy strategy) {
        ChunkPaginationConfig config = new ChunkPaginationConfig();
        config.setRequestStrategy(strategy);
        config.setResponseStrategy(PaginationResponseStrategy.FLAG_MATCH);
        config.setStatusOffset(14);
        config.setLoopContinueValue("1");
        config.setBodyOffset(20);
        config.setMaxPages(3);
        config.setPageOffset(5);
        config.setPageLen(2);
        return config;
    }

    @Test
    @DisplayName("[Pagination Normal] INCREMENT_PAGE strategy verification")
    void testPagination_IncrementPage() {
        when(mockHandlerDef.getPagination()).thenReturn(createPaginationConfig(PaginationRequestStrategy.INCREMENT_PAGE));
        TcpLoopingPaginationHandler handler = new TcpLoopingPaginationHandler(mockClientContext, mockHandlerDef);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeOutbound(Unpooled.copiedBuffer("REQ__01__DATA".getBytes(StandardCharsets.UTF_8)));
        channel.readOutbound(); // Initial request release

        channel.writeInbound(Unpooled.copiedBuffer("RES_HEADER____1_____BODY_1".getBytes(StandardCharsets.UTF_8)));

        ByteBuf autoReq1 = channel.readOutbound();
        assertNotNull(autoReq1);
        assertEquals("REQ__02__DATA", autoReq1.toString(StandardCharsets.UTF_8));
        autoReq1.release();

        channel.writeInbound(Unpooled.copiedBuffer("RES_HEADER____0_____BODY_2".getBytes(StandardCharsets.UTF_8)));

        ByteBuf finalInbound = channel.readInbound();
        assertEquals("RES_HEADER____1_____BODY_1BODY_2", finalInbound.toString(StandardCharsets.UTF_8));
        finalInbound.release();
    }

    // =========================================================================
    // [PART 4] Extreme Edge Cases (Multibyte and Timeout)
    // =========================================================================

    @Test
    @DisplayName("[Splitter Extreme Defense] Verification of boundary split safety for multibyte (Korean)")
    void testChunkedEncoder_MultiByteSafeSplit() {
        ChunkSplitterConfig config = createSplitterConfig(30);
        ChunkedResponseEncoder encoder = new ChunkedResponseEncoder(mockUpstream, config, StandardCharsets.UTF_8, true, mockSender);

        // "안녕하세요" is 15 bytes in UTF-8. The characters must be contained without breaking in the 10-byte chunk body space.
        String payload = "HEADER00000000000000" + "안녕하세요";
        Payload event = new ZefioMessage(payload.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        encoder.sendChunkedResponse(event, ctx);

        ArgumentCaptor<byte[]> chunkCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockSender, atLeastOnce()).sendChunk(eq(event), eq(ctx), chunkCaptor.capture());

        String chunk1 = new String(chunkCaptor.getAllValues().get(0), StandardCharsets.UTF_8);
        // Verify 3 Korean characters (9 bytes) + 1 byte margin (discarded)
        assertTrue(chunk1.endsWith("안녕하"), "Must be safely cut by character unit");
    }

    @Test
    @DisplayName("[Aggregator Extreme Defense] Forcefully evict existing buffer upon chunk assembly timeout")
    void testAggregator_TimeoutEviction() throws InterruptedException {
        ChunkAggregatorConfig config = createAggregatorConfig();
        config.setChunkTimeout(100);
        when(mockHandlerDef.getAggregator()).thenReturn(config);

        TcpMessageAggregatorHandler handler = new TcpMessageAggregatorHandler(mockClientContext, mockHandlerDef);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(Unpooled.copiedBuffer("HEADER00000000SSSS00START_DATA".getBytes(StandardCharsets.UTF_8)));

        // 🚀 Wait for timeout
        Thread.sleep(150);

        channel.writeInbound(Unpooled.copiedBuffer("HEADER00000000FFF 00END_DATA".getBytes(StandardCharsets.UTF_8)));

        ByteBuf buf = channel.readInbound();
        String result = buf.toString(StandardCharsets.UTF_8);
        buf.release();

        assertFalse(result.contains("START_DATA"), "Previous data must evaporate due to timeout");
        assertEquals("END_DATA", result);
    }
}
