using System.Text;
using System.Diagnostics;
using System.Text.Json;
using QRCoder;
using QrTransferSender;

static void Assert(bool condition, string message)
{
    if (!condition)
    {
        throw new InvalidOperationException(message);
    }
}

var integerSide = QrDisplayControl.CalculateIntegerScaledSide(361, 2000, out var integerPixelsPerModule);
Assert(integerSide == 1805 && integerPixelsPerModule == 5,
    $"整数模块缩放计算错误：side={integerSide}, ppm={integerPixelsPerModule}");
var exactSide = QrDisplayControl.CalculateIntegerScaledSide(361, 2166, out var exactPixelsPerModule);
Assert(exactSide == 2166 && exactPixelsPerModule == 6, "完整整数倍尺寸没有保持原始边界");
var undersizedSide = QrDisplayControl.CalculateIntegerScaledSide(361, 300, out var undersizedPixelsPerModule);
Assert(undersizedSide == 300 && undersizedPixelsPerModule == 0, "空间不足时应明确标记为非整数缩放");
Assert(QrDisplayControl.CalculateRequestedSide(1800, 100) == 1200,
    "100%二维码尺寸没有为继续放大保留空间");
Assert(QrDisplayControl.CalculateRequestedSide(1800, 150) == 1800,
    "150%二维码尺寸没有填满可用显示区域");

var crc = Crc32.Compute(Encoding.ASCII.GetBytes("123456789"));
Assert(crc == 0xCBF43926u, $"CRC32 测试失败：{crc:X8}");

var cases = new[]
{
    Array.Empty<byte>(),
    new byte[] { 0 },
    new byte[] { 0, 255 },
    Encoding.UTF8.GetBytes("Hello, 世界"),
    Enumerable.Range(0, 1024).Select(i => (byte)(i % 256)).ToArray()
};

foreach (var data in cases)
{
    var encoded = Base45.Encode(data);
    var decoded = Base45.Decode(encoded);
    Assert(data.SequenceEqual(decoded), $"Base45 往返测试失败，长度 {data.Length}");
}

Assert(Base45.Encode(Encoding.ASCII.GetBytes("AB")) == "BB8", "Base45 RFC 示例 AB 失败");
Assert(Base45.Encode(Encoding.ASCII.GetBytes("Hello!!")) == "%69 VD92EX0", "Base45 RFC 示例 Hello!! 失败");

var frame = FrameCodec.Create('D', "0123456789", 35, 1295, Encoding.ASCII.GetBytes("payload"));
Assert(frame.Length > FrameCodec.HeaderLength, "帧没有载荷");
Assert(frame[..4] == "QTX1", "魔数错误");
Assert(frame[4] == 'D', "类型错误");
Assert(frame.Substring(5, 10) == "0123456789", "会话 ID 错误");
Assert(frame.Substring(15, 6) == "00000Z", "序号 Base36 错误");
Assert(frame.Substring(21, 6) == "0000ZZ", "总数 Base36 错误");

var emptySession = TransferSession.Create(Array.Empty<byte>(), "空文件.bin", "application/octet-stream", 600);
Assert(emptySession.TotalChunks == 1, "空文件必须包含一个空数据帧");
Assert(emptySession.Schedule.Count >= 5, "发送计划缺少控制帧");
Assert(emptySession.Schedule.Count(index => index == -1) == 1, "单数据帧轮次必须包含一个元数据帧");
var singleMetadata = JsonDocument.Parse(Base45.Decode(emptySession.StartFrame[FrameCodec.HeaderLength..]));
Assert(singleMetadata.RootElement.GetProperty("v").GetInt32() == 2, "测试协议版本没有升级到2");
Assert(singleMetadata.RootElement.GetProperty("q").GetInt32() == 1, "单码元数据布局错误");
var quadSession = TransferSession.Create(Enumerable.Range(0, 12000).Select(i => (byte)i).ToArray(),
    "quad.bin", "application/octet-stream", 2000, QrLayoutMode.Quad, 4);
var quadMetadata = JsonDocument.Parse(Base45.Decode(quadSession.StartFrame[FrameCodec.HeaderLength..]));
Assert(quadMetadata.RootElement.GetProperty("q").GetInt32() == 4, "四码元数据没有声明q=4");
Assert(quadMetadata.RootElement.GetProperty("g").GetInt32() == 4, "四码元数据间距没有写入");
using (var quadPage = await FramePlaybackEngine.PrepareSpecificAsync(quadSession, 0, 1))
using (var quadImage = quadPage.TakeImage())
{
    Assert(quadImage.Width == quadImage.Height && quadImage.Width > 300, "田字形复合页面尺寸无效");
    Assert(quadPage.LayoutMode == QrLayoutMode.Quad, "指定补帧没有保持田字四码布局");
}

var schedulingData = Enumerable.Range(0, 500 * 100).Select(i => (byte)(i % 251)).ToArray();
var schedulingSession = TransferSession.Create(schedulingData, "schedule.bin", "application/octet-stream", 100);
var secondRoundData = schedulingSession.GetScheduleForRound(2).Where(index => index >= 0).ToArray();
var expectedMetadataFrames = (schedulingSession.TotalChunks + TransferSession.MetadataRepeatDataFrames - 1) / TransferSession.MetadataRepeatDataFrames;
Assert(schedulingSession.Schedule.Count(index => index == -1) == expectedMetadataFrames, "首轮没有按每64个数据帧循环插入元数据");
Assert(schedulingSession.GetScheduleForRound(2).Count(index => index == -1) == expectedMetadataFrames, "后续循环没有继续发送元数据");
Assert(schedulingSession.GetScheduleForRound(2)[0] == -1, "每一轮必须以元数据帧开始");
Assert(secondRoundData.Length == schedulingSession.TotalChunks, "补发轮次的数据帧数量不正确");
Assert(secondRoundData.Distinct().Count() == schedulingSession.TotalChunks, "补发轮次存在重复或遗漏的数据帧");
Assert(!secondRoundData.Take(32).SequenceEqual(Enumerable.Range(0, 32)), "补发轮次仍在按连续区间顺序发送");
Assert(secondRoundData.Take(3).SequenceEqual(new[] { 0, 32, 64 }), "500 帧的交错补发起始顺序不正确");
var retryFrame = schedulingSession.GetFrameForScheduleEntry(0, 2);
Assert(retryFrame[4] == 'W', "补发轮次必须使用白化 W 帧");
Assert(TransferSession.GetWhiteningSeedForRound(1) == 1
       && TransferSession.GetWhiteningSeedForRound(2) == 2
       && TransferSession.GetWhiteningSeedForRound(3) == 3,
    "自动补发轮次没有逐轮更换白化种子");
const int requestedDisplayNumber = 237;
var requestedRefillFrame = schedulingSession.GetFrameForScheduleEntry(requestedDisplayNumber - 1, 2);
Assert(requestedRefillFrame.Substring(15, 6) == Base36.Encode(requestedDisplayNumber - 1, 6), "手机显示编号与指定补帧的协议索引没有正确换算");
var retryPayload = Base45.Decode(retryFrame[FrameCodec.HeaderLength..]);
Assert(retryPayload.Length == 101 && retryPayload[0] == 2, "W 帧没有正确携带轮次种子");
var decodedRetry = retryPayload.AsSpan(1).ToArray();
PayloadWhitening.Apply(decodedRetry, schedulingSession.SessionId, 0, retryPayload[0]);
Assert(decodedRetry.AsSpan().SequenceEqual(schedulingData.AsSpan(0, 100)), "W 帧反白化后没有还原原始分片");
var thirdRoundFrame = schedulingSession.GetFrameForScheduleEntry(0, 3);
Assert(thirdRoundFrame[4] == 'W' && thirdRoundFrame != retryFrame, "不同补发轮次应生成不同的白化二维码内容");
Assert(Base45.Decode(retryFrame[FrameCodec.HeaderLength..])[0]
       != Base45.Decode(thirdRoundFrame[FrameCodec.HeaderLength..])[0],
    "相邻自动补发轮次在帧内携带了相同白化种子");

var firstRoundFrame = schedulingSession.GetFrameForScheduleEntry(0, 1);
Assert(firstRoundFrame[4] == 'W', "首轮也必须使用白化 W 帧");
var firstRoundPayload = Base45.Decode(firstRoundFrame[FrameCodec.HeaderLength..]);
var decodedFirstRound = firstRoundPayload.AsSpan(1).ToArray();
PayloadWhitening.Apply(decodedFirstRound, schedulingSession.SessionId, 0, firstRoundPayload[0]);
Assert(decodedFirstRound.AsSpan().SequenceEqual(schedulingData.AsSpan(0, 100)), "首轮 W 帧反白化失败");

var officeLikeData = new byte[420];
for (var index = 0; index < officeLikeData.Length; index += 32) officeLikeData[index] = 0xD0;
var officeSession = TransferSession.Create(officeLikeData, "测试文档.doc", "application/msword", 420);
var officeFrame = officeSession.GetFrameForScheduleEntry(0, 1);
var officePayload = Base45.Decode(officeFrame[FrameCodec.HeaderLength..]);
var oneBits = officePayload.AsSpan(1).ToArray().Sum(value => System.Numerics.BitOperations.PopCount((uint)value));
var bitRatio = oneBits / (double)((officePayload.Length - 1) * 8);
Assert(bitRatio is > 0.38 and < 0.62, $"Office 类重复数据白化后仍不均衡：{bitRatio:P1}");

var qrPayload = Enumerable.Range(0, 600).Select(i => (byte)(i % 256)).ToArray();
var qrSession = TransferSession.Create(qrPayload, "office.xls", "application/vnd.ms-excel", 600);
var qrFrame = qrSession.GetFrameForScheduleEntry(0, 1);
using (var qrData = QRCodeGenerator.GenerateQrCode(qrFrame, QRCodeGenerator.ECCLevel.Q))
using (var qr = new PngByteQRCode(qrData))
{
    var png = qr.GetGraphic(8, drawQuietZones: true);
    Assert(png.Length > 1000, "600 字节数据帧未能生成有效二维码 PNG");
    Assert(png[0] == 0x89 && png[1] == 0x50 && png[2] == 0x4E && png[3] == 0x47, "二维码输出不是 PNG");
}

var twoKilobytePayload = Enumerable.Range(0, 2048).Select(i => (byte)((i * 73 + 19) % 256)).ToArray();
var twoKilobyteSession = TransferSession.Create(twoKilobytePayload, "2kb-capacity.bin", "application/octet-stream", 2048);
Assert(twoKilobyteSession.ChunkSize == 2048 && twoKilobyteSession.TotalChunks == 1, "2048 字节没有作为一个完整数据分片发送");
var twoKilobyteFrame = twoKilobyteSession.GetFrameForScheduleEntry(0, 1);
Assert(twoKilobyteFrame.Length > 2350, "2048 字节帧未进入 M 级容量模式");
using (var qrData = QRCodeGenerator.GenerateQrCode(twoKilobyteFrame, QRCodeGenerator.ECCLevel.M))
using (var qr = new PngByteQRCode(qrData))
{
    Assert(qrData.ModuleMatrix.Count <= 185, $"2048 字节帧超过二维码 Version 40 容量：{qrData.ModuleMatrix.Count} 模块（含白边）");
    var png = qr.GetGraphic(8, drawQuietZones: true);
    Assert(png.Length > 1000, "2048 字节数据帧未能生成 M 级二维码 PNG");
    Assert(png[0] == 0x89 && png[1] == 0x50 && png[2] == 0x4E && png[3] == 0x47, "2048 字节二维码输出不是 PNG");
}

var maximumPayload = Enumerable.Range(0, 2800).Select(i => (byte)((i * 97 + 31) % 256)).ToArray();
var maximumSession = TransferSession.Create(maximumPayload, "l-level-near-maximum.bin", "application/octet-stream", 2800);
var maximumFrame = maximumSession.GetFrameForScheduleEntry(0, 1);
Assert(maximumSession.TotalChunks == 1 && maximumFrame.Length == 4237, $"L 级大容量帧长度计算错误：{maximumFrame.Length}");
var invalidAlphanumeric = maximumFrame.Where(character => !"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:".Contains(character)).Distinct().ToArray();
Assert(invalidAlphanumeric.Length == 0, $"极限帧包含非二维码字母数字字符：{string.Join(',', invalidAlphanumeric)}");
using (var qrData = QRCodeGenerator.GenerateQrCode(maximumFrame, QRCodeGenerator.ECCLevel.L))
using (var qr = new PngByteQRCode(qrData))
{
    Assert(qrData.ModuleMatrix.Count <= 185, "2800 字节帧超过二维码 Version 40-L 容量");
    var png = qr.GetGraphic(8, drawQuietZones: true);
    Assert(png.Length > 1000, "2800 字节数据帧未能生成 L 级二维码 PNG");
    using var moduleBitmap = QrModuleBitmap.Render(qrData);
    for (var sampleY = 0; sampleY < moduleBitmap.Height; sampleY += 17)
    {
        for (var sampleX = 0; sampleX < moduleBitmap.Width; sampleX += 19)
        {
            var expectedBlack = qrData.ModuleMatrix[sampleY][sampleX];
            var pixel = moduleBitmap.GetPixel(sampleX, sampleY);
            Assert(expectedBlack ? pixel.R < 32 : pixel.R > 223, "1bpp 模块位图与二维码矩阵不一致");
        }
    }
}

var oversizedProtocolRejected = false;
try
{
    _ = TransferSession.Create(new byte[2801], "too-large.bin", "application/octet-stream", 2801);
}
catch (ArgumentOutOfRangeException)
{
    oversizedProtocolRejected = true;
}
Assert(oversizedProtocolRejected, "协议没有拒绝超过 2800 字节的分片");

const int renderBenchmarkFrames = 60;
var renderClock = Stopwatch.StartNew();
Parallel.For(0, renderBenchmarkFrames, new ParallelOptions { MaxDegreeOfParallelism = 2 }, benchmarkIndex =>
{
    var benchmarkFrame = maximumSession.GetFrameForScheduleEntry(0, benchmarkIndex + 1);
    using var qrData = QRCodeGenerator.GenerateQrCode(benchmarkFrame, QRCodeGenerator.ECCLevel.L);
    using var bitmap = QrModuleBitmap.Render(qrData);
    Assert(bitmap.Width >= 21 && bitmap.Height == bitmap.Width, "V1.7 模块位图无效");
});
renderClock.Stop();
var renderFps = renderBenchmarkFrames / renderClock.Elapsed.TotalSeconds;
Console.WriteLine($"V1.8.4 2800字节单码后台生成基准：{renderFps:F1}帧/秒（四码页面会并行预生成）");
Assert(FramePlaybackEngine.GetPresentationMilliseconds(-1, 30, 1, 1) == 500, "首次元数据没有保持500ms");
Assert(FramePlaybackEngine.GetPresentationMilliseconds(-1, 30, 1, 66) == 100, "首轮循环元数据没有保持100ms");
Assert(FramePlaybackEngine.GetPresentationMilliseconds(-1, 30, 2, 1) == 100, "后续轮次元数据没有保持100ms");
Assert(Math.Abs(FramePlaybackEngine.GetPresentationMilliseconds(0, 30) - 1000.0 / 30) < 0.001, "30FPS 数据帧间隔被元数据策略改变");

Console.WriteLine("协议自检全部通过。");
