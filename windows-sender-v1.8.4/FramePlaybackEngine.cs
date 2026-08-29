using QRCoder;
using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Threading.Channels;

namespace QrTransferSender;

public sealed class PreparedQrFrame : IDisposable
{
    private Bitmap? _image;

    public PreparedQrFrame(Bitmap image, IReadOnlyList<int> scheduleEntries, int round, int displayIndex,
        int scheduleCount, int nextRound, int nextScheduleIndex, string eccText, QrLayoutMode layoutMode)
    {
        _image = image;
        ScheduleEntries = scheduleEntries;
        Round = round;
        DisplayIndex = displayIndex;
        ScheduleCount = scheduleCount;
        NextRound = nextRound;
        NextScheduleIndex = nextScheduleIndex;
        EccText = eccText;
        LayoutMode = layoutMode;
    }

    public IReadOnlyList<int> ScheduleEntries { get; }
    public int ScheduleEntry => ScheduleEntries.Count == 0 ? -2 : ScheduleEntries[0];
    public bool IsMetadata => ScheduleEntries.Count > 0 && ScheduleEntries.All(entry => entry == -1);
    public bool IsEnd => ScheduleEntries.Count > 0 && ScheduleEntries.All(entry => entry == -2);
    public int Round { get; }
    public int DisplayIndex { get; }
    public int ScheduleCount { get; }
    public int NextRound { get; }
    public int NextScheduleIndex { get; }
    public string EccText { get; }
    public QrLayoutMode LayoutMode { get; }

    public Bitmap TakeImage()
    {
        var image = _image ?? throw new ObjectDisposedException(nameof(PreparedQrFrame));
        _image = null;
        return image;
    }

    public void Dispose()
    {
        _image?.Dispose();
        _image = null;
    }
}

public sealed class FramePlaybackEngine : IDisposable
{
    private const int BufferCapacity = 6;
    public const int InitialMetadataDwellMilliseconds = 500;
    public const int MetadataDwellMilliseconds = 100;
    private readonly Func<int> _targetFps;
    private readonly Action<Action> _postToUi;
    private readonly Action<PreparedQrFrame> _present;
    private readonly Action<Exception> _reportError;
    private CancellationTokenSource? _cancellation;
    private int _generation;
    private int _uiPresentationPending;
    private bool _timerResolutionRaised;

    public FramePlaybackEngine(Func<int> targetFps, Action<Action> postToUi,
        Action<PreparedQrFrame> present, Action<Exception> reportError)
    {
        _targetFps = targetFps;
        _postToUi = postToUi;
        _present = present;
        _reportError = reportError;
    }

    public void Start(TransferSession session, int round, int scheduleIndex)
    {
        Stop();
        var generation = Interlocked.Increment(ref _generation);
        var cancellation = new CancellationTokenSource();
        _cancellation = cancellation;
        var channel = Channel.CreateBounded<PreparedQrFrame>(new BoundedChannelOptions(BufferCapacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = true,
            AllowSynchronousContinuations = false
        });

        RaiseTimerResolution();
        _ = Task.Run(() => RenderLoopAsync(session, round, scheduleIndex, channel.Writer, cancellation.Token));
        _ = Task.Factory.StartNew(
            () => PresentationLoop(channel.Reader, generation, cancellation.Token),
            cancellation.Token,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default);
    }

    public void Stop()
    {
        Interlocked.Increment(ref _generation);
        var cancellation = Interlocked.Exchange(ref _cancellation, null);
        if (cancellation is not null)
        {
            cancellation.Cancel();
            cancellation.Dispose();
        }
        RestoreTimerResolution();
    }

    public static Task<PreparedQrFrame> PrepareSpecificAsync(TransferSession session, int dataIndex, int round,
        CancellationToken token = default)
    {
        return Task.Run(() =>
        {
            token.ThrowIfCancellationRequested();
            var actualRound = Math.Max(1, round);
            var raw = session.GetFrameForScheduleEntry(dataIndex, actualRound);
            var count = session.LayoutMode == QrLayoutMode.Quad ? 4 : 1;
            var items = Enumerable.Range(0, count).Select(_ => new QrItem(raw, dataIndex)).ToArray();
            var specification = new FrameSpecification(items, new[] { dataIndex }, actualRound, 1, 1,
                actualRound, 0, session.LayoutMode, session.QuadGapPercent);
            return RenderPage(specification);
        }, token);
    }

    private async Task RenderLoopAsync(TransferSession session, int initialRound, int initialIndex,
        ChannelWriter<PreparedQrFrame> writer, CancellationToken token)
    {
        const int batchSize = 8;
        PreparedQrFrame?[] rendered = new PreparedQrFrame?[batchSize];
        try
        {
            var round = Math.Max(1, initialRound);
            var index = Math.Max(0, initialIndex);
            while (!token.IsCancellationRequested)
            {
                var specifications = new List<FrameSpecification>(batchSize);
                while (specifications.Count < batchSize)
                {
                    var specification = CreateNextSpecification(session, round, index);
                    specifications.Add(specification);
                    round = specification.NextRound;
                    index = specification.NextScheduleIndex;
                }

                rendered = new PreparedQrFrame?[specifications.Count];
                await Parallel.ForEachAsync(
                    Enumerable.Range(0, specifications.Count),
                    new ParallelOptions
                    {
                        CancellationToken = token,
                        MaxDegreeOfParallelism = session.LayoutMode == QrLayoutMode.Quad ? 4 : 2
                    },
                    (position, _) =>
                    {
                        rendered[position] = RenderPage(specifications[position]);
                        return ValueTask.CompletedTask;
                    }).ConfigureAwait(false);

                for (var position = 0; position < rendered.Length; position++)
                {
                    var frame = rendered[position] ?? throw new InvalidOperationException("二维码后台生成结果为空。");
                    await writer.WriteAsync(frame, token).ConfigureAwait(false);
                    rendered[position] = null;
                }
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            PostError(error);
        }
        finally
        {
            foreach (var frame in rendered) frame?.Dispose();
            writer.TryComplete();
        }
    }

    private static FrameSpecification CreateNextSpecification(TransferSession session, int round, int index)
    {
        IReadOnlyList<int> schedule;
        while (true)
        {
            schedule = session.GetScheduleForRound(round);
            if (index < schedule.Count) break;
            round++;
            index = 0;
        }

        var qrCount = session.LayoutMode == QrLayoutMode.Quad ? 4 : 1;
        var firstEntry = schedule[index];
        var displayEntries = new List<int>(qrCount);
        var items = new List<QrItem>(qrCount);
        var consumed = 0;

        if (firstEntry < 0)
        {
            var raw = session.GetFrameForScheduleEntry(firstEntry, round);
            for (var slot = 0; slot < qrCount; slot++) items.Add(new QrItem(raw, firstEntry));
            displayEntries.Add(firstEntry);
            consumed = 1;
        }
        else
        {
            while (displayEntries.Count < qrCount && index + consumed < schedule.Count)
            {
                var entry = schedule[index + consumed];
                if (entry < 0) break;
                displayEntries.Add(entry);
                items.Add(new QrItem(session.GetFrameForScheduleEntry(entry, round), entry));
                consumed++;
            }
            while (items.Count < qrCount) items.Add(items[^1]);
        }

        var nextRound = round;
        var nextIndex = index + consumed;
        if (nextIndex >= schedule.Count)
        {
            nextRound++;
            nextIndex = 0;
        }
        return new FrameSpecification(items, displayEntries, round, index + consumed, schedule.Count,
            nextRound, nextIndex, session.LayoutMode, session.QuadGapPercent);
    }

    private void PresentationLoop(ChannelReader<PreparedQrFrame> reader, int generation, CancellationToken token)
    {
        var nextDeadline = Stopwatch.GetTimestamp();
        try
        {
            while (!token.IsCancellationRequested)
            {
                WaitUntil(nextDeadline, token);
                var now = Stopwatch.GetTimestamp();
                var fps = Math.Clamp(_targetFps(), 2, 60);
                var normalInterval = Math.Max(1L, Stopwatch.Frequency / fps);

                while (Volatile.Read(ref _uiPresentationPending) != 0)
                {
                    token.ThrowIfCancellationRequested();
                    Thread.Sleep(1);
                }
                if (!reader.TryRead(out var frame))
                {
                    nextDeadline = now + Math.Min(normalInterval, Math.Max(1L, Stopwatch.Frequency / 250));
                    continue;
                }
                Interlocked.Exchange(ref _uiPresentationPending, 1);
                var dwell = frame.IsMetadata
                    ? Stopwatch.Frequency * GetMetadataDwellMilliseconds(frame.Round, frame.DisplayIndex) / 1000
                    : normalInterval;
                nextDeadline = Stopwatch.GetTimestamp() + Math.Max(1L, dwell);

                _postToUi(() =>
                {
                    try
                    {
                        if (generation == Volatile.Read(ref _generation) && !token.IsCancellationRequested) _present(frame);
                        else frame.Dispose();
                    }
                    catch (Exception error)
                    {
                        frame.Dispose();
                        _reportError(error);
                    }
                    finally
                    {
                        Volatile.Write(ref _uiPresentationPending, 0);
                    }
                });
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            PostError(error);
        }
        finally
        {
            while (reader.TryRead(out var remaining)) remaining.Dispose();
        }
    }

    private static PreparedQrFrame RenderPage(FrameSpecification specification)
    {
        var images = new Bitmap[specification.Items.Count];
        try
        {
            var eccTexts = new string[specification.Items.Count];
            for (var slot = 0; slot < specification.Items.Count; slot++)
            {
                (images[slot], eccTexts[slot]) = RenderQr(specification.Items[slot].Raw);
            }
            Bitmap output;
            if (specification.LayoutMode == QrLayoutMode.Quad)
            {
                output = ComposeQuad(images, specification.QuadGapPercent);
                foreach (var image in images) image.Dispose();
                Array.Clear(images);
            }
            else
            {
                output = images[0];
                images[0] = null!;
            }
            var eccText = string.Join("/", eccTexts.Distinct());
            return new PreparedQrFrame(output, specification.DisplayEntries, specification.Round,
                specification.DisplayIndex, specification.ScheduleCount, specification.NextRound,
                specification.NextScheduleIndex, eccText, specification.LayoutMode);
        }
        finally
        {
            foreach (var image in images) image?.Dispose();
        }
    }

    private static (Bitmap Image, string EccText) RenderQr(string raw)
    {
        var eccLevel = raw.Length switch
        {
            <= 2400 => QRCodeGenerator.ECCLevel.Q,
            <= 3370 => QRCodeGenerator.ECCLevel.M,
            _ => QRCodeGenerator.ECCLevel.L
        };
        var eccText = eccLevel switch
        {
            QRCodeGenerator.ECCLevel.L => "L（极限容量模式）",
            QRCodeGenerator.ECCLevel.M => "M（大容量模式）",
            _ => "Q"
        };
        using var qrData = QRCodeGenerator.GenerateQrCode(raw, eccLevel);
        return (QrModuleBitmap.Render(qrData), eccText);
    }

    private static Bitmap ComposeQuad(IReadOnlyList<Bitmap> images, int gapPercent)
    {
        if (images.Count != 4) throw new ArgumentException("田字形页面必须包含四个二维码。", nameof(images));
        var cellSide = images.Max(image => Math.Max(image.Width, image.Height));
        var gap = Math.Max(0, (int)Math.Round(cellSide * Math.Clamp(gapPercent, 0, 20) / 100.0));
        var totalSide = cellSide * 2 + gap;
        var output = new Bitmap(totalSide, totalSide, PixelFormat.Format24bppRgb);
        using var graphics = Graphics.FromImage(output);
        graphics.Clear(Color.White);
        graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
        graphics.SmoothingMode = SmoothingMode.None;
        graphics.PixelOffsetMode = PixelOffsetMode.Half;
        graphics.CompositingQuality = CompositingQuality.HighSpeed;
        graphics.CompositingMode = CompositingMode.SourceCopy;
        for (var slot = 0; slot < 4; slot++)
        {
            var column = slot % 2;
            var row = slot / 2;
            var cellX = column * (cellSide + gap);
            var cellY = row * (cellSide + gap);
            var x = cellX + (cellSide - images[slot].Width) / 2;
            var y = cellY + (cellSide - images[slot].Height) / 2;
            graphics.DrawImageUnscaled(images[slot], x, y);
        }
        return output;
    }

    private void PostError(Exception error)
    {
        try { _postToUi(() => _reportError(error)); }
        catch { }
    }

    private static void WaitUntil(long deadline, CancellationToken token)
    {
        while (true)
        {
            token.ThrowIfCancellationRequested();
            var remainingTicks = deadline - Stopwatch.GetTimestamp();
            if (remainingTicks <= 0) return;
            var remainingMs = remainingTicks * 1000.0 / Stopwatch.Frequency;
            if (remainingMs > 2.0) Thread.Sleep(Math.Max(1, (int)remainingMs - 1));
            else if (remainingMs > 0.35) Thread.Yield();
            else Thread.SpinWait(40);
        }
    }

    private void RaiseTimerResolution()
    {
        if (_timerResolutionRaised) return;
        _timerResolutionRaised = timeBeginPeriod(1) == 0;
    }

    private void RestoreTimerResolution()
    {
        if (!_timerResolutionRaised) return;
        timeEndPeriod(1);
        _timerResolutionRaised = false;
    }

    public void Dispose() => Stop();

    public static double GetPresentationMilliseconds(int scheduleEntry, int fps, int round = 1, int displayIndex = 1)
    {
        return scheduleEntry == -1
            ? GetMetadataDwellMilliseconds(round, displayIndex)
            : 1000.0 / Math.Clamp(fps, 2, 60);
    }

    private static int GetMetadataDwellMilliseconds(int round, int displayIndex)
    {
        return round == 1 && displayIndex == 1 ? InitialMetadataDwellMilliseconds : MetadataDwellMilliseconds;
    }

    [DllImport("winmm.dll", ExactSpelling = true)]
    private static extern uint timeBeginPeriod(uint period);

    [DllImport("winmm.dll", ExactSpelling = true)]
    private static extern uint timeEndPeriod(uint period);

    private sealed record QrItem(string Raw, int Entry);

    private sealed record FrameSpecification(
        IReadOnlyList<QrItem> Items,
        IReadOnlyList<int> DisplayEntries,
        int Round,
        int DisplayIndex,
        int ScheduleCount,
        int NextRound,
        int NextScheduleIndex,
        QrLayoutMode LayoutMode,
        int QuadGapPercent);
}
