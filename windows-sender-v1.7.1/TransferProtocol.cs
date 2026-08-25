using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace QrTransferSender;

public sealed record TransferMetadata(
    int v,
    string n,
    string m,
    long s,
    int c,
    int t,
    string h,
    string z,
    string e,
    string w);

public sealed class TransferSession
{
    public const int MaxFileBytes = 25 * 1024 * 1024;
    public const int MetadataRepeatDataFrames = 64;

    private TransferSession(byte[] data, string fileName, string mimeType, int chunkSize)
    {
        Data = data;
        FileName = SanitizeFileName(fileName);
        MimeType = mimeType;
        ChunkSize = chunkSize;
        TotalChunks = Math.Max(1, (data.Length + chunkSize - 1) / chunkSize);
        SessionId = Convert.ToHexString(RandomNumberGenerator.GetBytes(5));
        Sha256 = Convert.ToHexString(SHA256.HashData(data));

        var metadata = new TransferMetadata(
            1,
            FileName,
            MimeType,
            data.LongLength,
            ChunkSize,
            TotalChunks,
            Sha256,
            "NONE",
            "NONE",
            "XORSHIFT32-V1");

        var metaBytes = JsonSerializer.SerializeToUtf8Bytes(metadata);
        StartFrame = FrameCodec.Create('S', SessionId, 0, TotalChunks, metaBytes);
        EndFrame = FrameCodec.Create('E', SessionId, TotalChunks, TotalChunks, Array.Empty<byte>());
        Schedule = BuildSchedule(TotalChunks, 1);
    }

    public byte[] Data { get; }
    public string FileName { get; }
    public string MimeType { get; }
    public int ChunkSize { get; }
    public int TotalChunks { get; }
    public string SessionId { get; }
    public string Sha256 { get; }
    public string StartFrame { get; }
    public string EndFrame { get; }
    public IReadOnlyList<int> Schedule { get; }

    public static TransferSession Create(byte[] data, string fileName, string mimeType, int chunkSize)
    {
        if (data.Length > MaxFileBytes)
        {
            throw new InvalidOperationException($"当前版本单次最多发送 {MaxFileBytes / 1024 / 1024} MiB。");
        }

        if (chunkSize is < 100 or > 2800)
        {
            throw new ArgumentOutOfRangeException(nameof(chunkSize), "V1.7.1 分片大小必须在 100～2800 字节之间。");
        }

        return new TransferSession(data, fileName, mimeType, chunkSize);
    }

    public IReadOnlyList<int> GetScheduleForRound(int round)
    {
        if (round < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(round));
        }

        return round == 1 ? Schedule : BuildSchedule(TotalChunks, round);
    }

    public string GetFrameForScheduleEntry(int entry, int round = 1)
    {
        return entry switch
        {
            -1 => StartFrame,
            -2 => EndFrame,
            _ => CreateDataFrame(entry, round)
        };
    }

    private string CreateDataFrame(int index, int round)
    {
        var offset = index * ChunkSize;
        var length = Math.Min(ChunkSize, Math.Max(0, Data.Length - offset));
        var seed = (byte)(((round - 1) % 255) + 1);
        var whitenedChunk = new byte[length + 1];
        whitenedChunk[0] = seed;
        Data.AsSpan(offset, length).CopyTo(whitenedChunk.AsSpan(1));
        PayloadWhitening.Apply(whitenedChunk.AsSpan(1), SessionId, index, seed);
        return FrameCodec.Create('W', SessionId, index, TotalChunks, whitenedChunk);
    }

    private static IReadOnlyList<int> BuildSchedule(int totalChunks, int round)
    {
        var metadataFrames = Math.Max(1, (totalChunks + MetadataRepeatDataFrames - 1) / MetadataRepeatDataFrames);
        var schedule = new List<int>(totalChunks + metadataFrames + 3);
        var dataFramesSinceMetadata = MetadataRepeatDataFrames;
        foreach (var index in BuildDataOrder(totalChunks, round))
        {
            if (dataFramesSinceMetadata >= MetadataRepeatDataFrames)
            {
                schedule.Add(-1);
                dataFramesSinceMetadata = 0;
            }

            schedule.Add(index);
            dataFramesSinceMetadata++;
        }

        schedule.AddRange([-2, -2, -2]);
        return schedule;
    }

    private static IEnumerable<int> BuildDataOrder(int totalChunks, int round)
    {
        if (round <= 1 || totalChunks <= 1)
        {
            for (var index = 0; index < totalChunks; index++)
            {
                yield return index;
            }
            yield break;
        }

        const int maximumBands = 16;
        var bandCount = Math.Min(maximumBands, totalChunks);
        var bandSize = (totalChunks + bandCount - 1) / bandCount;
        var rotation = (round - 2) % bandCount;

        for (var offset = 0; offset < bandSize; offset++)
        {
            for (var bandOffset = 0; bandOffset < bandCount; bandOffset++)
            {
                var band = (bandOffset + rotation) % bandCount;
                var index = band * bandSize + offset;
                if (index < totalChunks)
                {
                    yield return index;
                }
            }
        }
    }

    private static string SanitizeFileName(string name)
    {
        var clean = Path.GetFileName(name);
        if (string.IsNullOrWhiteSpace(clean))
        {
            return "received.bin";
        }

        foreach (var invalid in Path.GetInvalidFileNameChars())
        {
            clean = clean.Replace(invalid, '_');
        }

        return clean.Length <= 180 ? clean : clean[..180];
    }
}

public static class FrameCodec
{
    public const string Magic = "QTX1";
    public const int HeaderLength = 35;

    public static string Create(char type, string sessionId, int index, int total, ReadOnlySpan<byte> payload)
    {
        if (sessionId.Length != 10 || sessionId.Any(c => !Uri.IsHexDigit(c)))
        {
            throw new ArgumentException("会话 ID 必须是 10 位十六进制字符。", nameof(sessionId));
        }

        if (type is not ('S' or 'D' or 'R' or 'W' or 'E'))
        {
            throw new ArgumentOutOfRangeException(nameof(type));
        }

        var header = string.Concat(
            Magic,
            type,
            sessionId.ToUpperInvariant(),
            Base36.Encode(index, 6),
            Base36.Encode(total, 6),
            Crc32.Compute(payload).ToString("X8"));

        return header + Base45.Encode(payload);
    }
}

public static class PayloadWhitening
{
    public static void Apply(Span<byte> payload, string sessionId, int index, byte seed)
    {
        var state = 2166136261u;
        foreach (var character in sessionId)
        {
            state ^= character;
            state = unchecked(state * 16777619u);
        }
        state ^= unchecked((uint)(index + 1) * 0x9E3779B9u);
        state ^= unchecked((uint)seed * 0x85EBCA6Bu);
        if (state == 0) state = 0xA5366B4Du;

        for (var offset = 0; offset < payload.Length; offset++)
        {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            payload[offset] ^= (byte)state;
        }
    }
}

public static class Base36
{
    private const string Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static string Encode(int value, int width)
    {
        if (value < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value));
        }

        Span<char> buffer = stackalloc char[width];
        buffer.Fill('0');

        for (var i = width - 1; i >= 0 && value > 0; i--)
        {
            buffer[i] = Alphabet[value % 36];
            value /= 36;
        }

        if (value != 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "数值超过固定宽度。");
        }

        return new string(buffer);
    }
}

public static class Base45
{
    private const string Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    public static string Encode(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty)
        {
            return string.Empty;
        }

        var builder = new StringBuilder((data.Length * 3 + 1) / 2);
        var i = 0;
        while (i + 1 < data.Length)
        {
            var value = data[i] * 256 + data[i + 1];
            var c = value % 45;
            value /= 45;
            var d = value % 45;
            var e = value / 45;
            builder.Append(Alphabet[c]);
            builder.Append(Alphabet[d]);
            builder.Append(Alphabet[e]);
            i += 2;
        }

        if (i < data.Length)
        {
            var value = data[i];
            builder.Append(Alphabet[value % 45]);
            builder.Append(Alphabet[value / 45]);
        }

        return builder.ToString();
    }

    public static byte[] Decode(string text)
    {
        if (text.Length % 3 == 1)
        {
            throw new FormatException("无效的 Base45 长度。");
        }

        var output = new List<byte>(text.Length * 2 / 3 + 1);
        var i = 0;
        while (i < text.Length)
        {
            var c = ValueOf(text[i]);
            var d = ValueOf(text[i + 1]);

            if (i + 2 < text.Length)
            {
                var e = ValueOf(text[i + 2]);
                var value = c + d * 45 + e * 45 * 45;
                if (value > ushort.MaxValue)
                {
                    throw new FormatException("无效的 Base45 三字符组。");
                }

                output.Add((byte)(value / 256));
                output.Add((byte)(value % 256));
                i += 3;
            }
            else
            {
                var value = c + d * 45;
                if (value > byte.MaxValue)
                {
                    throw new FormatException("无效的 Base45 双字符组。");
                }

                output.Add((byte)value);
                i += 2;
            }
        }

        return output.ToArray();
    }

    private static int ValueOf(char c)
    {
        var value = Alphabet.IndexOf(c);
        return value >= 0 ? value : throw new FormatException($"无效的 Base45 字符：{c}");
    }
}

public static class Crc32
{
    private static readonly uint[] Table = BuildTable();

    public static uint Compute(ReadOnlySpan<byte> data)
    {
        var crc = 0xFFFFFFFFu;
        foreach (var value in data)
        {
            crc = Table[(crc ^ value) & 0xFF] ^ (crc >> 8);
        }

        return ~crc;
    }

    private static uint[] BuildTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < table.Length; i++)
        {
            var crc = i;
            for (var bit = 0; bit < 8; bit++)
            {
                crc = (crc & 1) != 0 ? 0xEDB88320u ^ (crc >> 1) : crc >> 1;
            }

            table[i] = crc;
        }

        return table;
    }
}

public static class MimeTypes
{
    private static readonly IReadOnlyDictionary<string, string> Types = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        [".txt"] = "text/plain",
        [".html"] = "text/html",
        [".json"] = "application/json",
        [".pdf"] = "application/pdf",
        [".zip"] = "application/zip",
        [".png"] = "image/png",
        [".jpg"] = "image/jpeg",
        [".jpeg"] = "image/jpeg",
        [".gif"] = "image/gif",
        [".webp"] = "image/webp",
        [".mp3"] = "audio/mpeg",
        [".mp4"] = "video/mp4",
        [".m4v"] = "video/mp4",
        [".mov"] = "video/quicktime",
        [".webm"] = "video/webm",
        [".ogv"] = "video/ogg",
        [".avi"] = "video/x-msvideo",
        [".mkv"] = "video/x-matroska",
        [".docx"] = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        [".xlsx"] = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        [".pptx"] = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    };

    public static string FromFileName(string fileName)
    {
        return Types.TryGetValue(Path.GetExtension(fileName), out var mime)
            ? mime
            : "application/octet-stream";
    }
}
