using QRCoder;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading.Channels;

namespace QrTransferSender;

public sealed class PreparedQrFrame : IDisposable
{
    private Bitmap? _image;

    public PreparedQrFrame(Bitmap image, int scheduleEntry, int round, int displayIndex, int scheduleCount,
        int nextRound, int nextScheduleIndex, string eccText)
    {
        _image = image;
        ScheduleEntry = scheduleEntry;
        Round = round;
        DisplayIndex = displayIndex;
        ScheduleCount = scheduleCount;
        NextRound = nextRound;
        NextScheduleIndex = nextScheduleIndex;
        EccText = eccText;
    }

    public int ScheduleEntry { get; }
    public int Round { get; }
    public int DisplayIndex { get; }
    public int ScheduleCount { get; }
    public int NextRound { get; }
    public int NextScheduleIndex { get; }
    public string EccText { get; }

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
    private const int BufferCapacity = 8;
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

    public static Task<PreparedQrFrame> PrepareSpecificAsync(TransferSession session, int dataIndex, int round, CancellationToken token = default)
    {
        return Task.Run(() =>
        {
            token.ThrowIfCancellationRequested();
            var raw = session.GetFrameForScheduleEntry(dataIndex, Math.Max(1, round));
            return RenderFrame(raw, dataIndex, Math.Max(1, round), 1, 1, Math.Max(1, round), 0);
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
                    var schedule = session.GetScheduleForRound(round);
                    if (index >= schedule.Count)
                    {
                        round++;
                        index = 0;
                        continue;
                    }

                    var entry = schedule[index];
                    var raw = session.GetFrameForScheduleEntry(entry, round);
                    var nextRound = round;
                    var nextIndex = index + 1;
                    if (nextIndex >= schedule.Count)
                    {
                        nextRound++;
                        nextIndex = 0;
                    }
                    specifications.Add(new FrameSpecification(raw, entry, round, index + 1, schedule.Count, nextRound, nextIndex));
                    round = nextRound;
                    index = nextIndex;
                }

                rendered = new PreparedQrFrame?[specifications.Count];
                await Parallel.ForEachAsync(
                    Enumerable.Range(0, specifications.Count),
                    new ParallelOptions { CancellationToken = token, MaxDegreeOfParallelism = 2 },
                    (position, _) =>
                    {
                        var specification = specifications[position];
                        rendered[position] = RenderFrame(
                            specification.Raw,
                            specification.Entry,
                            specification.Round,
                            specification.DisplayIndex,
                            specification.ScheduleCount,
                            specification.NextRound,
                            specification.NextScheduleIndex);
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
                var dwell = frame.ScheduleEntry == -1
                    ? Stopwatch.Frequency * GetMetadataDwellMilliseconds(frame.Round, frame.DisplayIndex) / 1000
                    : normalInterval;
                nextDeadline = Stopwatch.GetTimestamp() + Math.Max(1L, dwell);

                _postToUi(() =>
                {
                    try
                    {
                        if (generation == Volatile.Read(ref _generation) && !token.IsCancellationRequested)
                        {
                            _present(frame);
                        }
                        else
                        {
                            frame.Dispose();
                        }
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

    private static PreparedQrFrame RenderFrame(string raw, int entry, int round, int displayIndex, int scheduleCount,
        int nextRound, int nextScheduleIndex)
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
        var bitmap = QrModuleBitmap.Render(qrData);
        return new PreparedQrFrame(bitmap, entry, round, displayIndex, scheduleCount, nextRound, nextScheduleIndex, eccText);
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
            if (remainingMs > 2.0)
            {
                Thread.Sleep(Math.Max(1, (int)remainingMs - 1));
            }
            else if (remainingMs > 0.35)
            {
                Thread.Yield();
            }
            else
            {
                Thread.SpinWait(40);
            }
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

    public void Dispose()
    {
        Stop();
    }

    public static double GetPresentationMilliseconds(int scheduleEntry, int fps, int round = 1, int displayIndex = 1)
    {
        return scheduleEntry == -1
            ? GetMetadataDwellMilliseconds(round, displayIndex)
            : 1000.0 / Math.Clamp(fps, 2, 60);
    }

    private static int GetMetadataDwellMilliseconds(int round, int displayIndex)
    {
        return round == 1 && displayIndex == 1
            ? InitialMetadataDwellMilliseconds
            : MetadataDwellMilliseconds;
    }

    [DllImport("winmm.dll", ExactSpelling = true)]
    private static extern uint timeBeginPeriod(uint period);

    [DllImport("winmm.dll", ExactSpelling = true)]
    private static extern uint timeEndPeriod(uint period);

    private sealed record FrameSpecification(
        string Raw,
        int Entry,
        int Round,
        int DisplayIndex,
        int ScheduleCount,
        int NextRound,
        int NextScheduleIndex);
}
