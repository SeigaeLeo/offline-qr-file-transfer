using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace QrTransferSender;

public sealed class MainForm : Form
{
    private readonly Panel _settingsPanel = new();
    private readonly QrDisplayControl _qrDisplay = new();
    private readonly RadioButton _textMode = new();
    private readonly RadioButton _fileMode = new();
    private readonly TextBox _textInput = new();
    private readonly TextBox _filePath = new();
    private readonly Button _browseButton = new();
    private readonly NumericUpDown _fpsInput = new();
    private readonly NumericUpDown _chunkInput = new();
    private readonly NumericUpDown _qrScaleInput = new();
    private readonly Button _startButton = new();
    private readonly Button _pauseButton = new();
    private readonly Button _stopButton = new();
    private readonly Button _fullscreenButton = new();
    private readonly NumericUpDown _refillFrameInput = new();
    private readonly Button _refillButton = new();
    private readonly Button _resumeLoopButton = new();
    private readonly ProgressBar _progress = new();
    private readonly Label _status = new();
    private readonly Label _details = new();
    private readonly Label _fpsStatus = new();
    private readonly FramePlaybackEngine _playback;

    private TransferSession? _session;
    private IReadOnlyList<int> _roundSchedule = Array.Empty<int>();
    private int _scheduleIndex;
    private int _round;
    private long _sentFrameCount;
    private string _currentEccText = "Q";
    private bool _paused;
    private bool _specificRefillMode;
    private bool _fullscreen;
    private FormBorderStyle _previousBorder;
    private Rectangle _previousBounds;
    private int _targetFps = 15;
    private long _lastPresentationTicks;
    private long _fpsWindowStartTicks;
    private int _fpsWindowFrames;
    private double _actualFps;
    private double _actualFrameIntervalMs;
    private double _intervalWindowTotalMs;
    private int _intervalWindowSamples;
    private long _lastDetailsTicks;
    private Rectangle _scaleBaseBounds;
    private bool _scaleWindowExpanded;

    public MainForm()
    {
        Text = "离线二维码发送器 V1.7.1";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(980, 760);
        Size = new Size(1220, 900);
        BackColor = Color.FromArgb(244, 246, 250);
        KeyPreview = true;

        BuildInterface();
        _qrDisplay.ScalePercent = Math.Min(100, (int)_qrScaleInput.Value);
        _targetFps = (int)_fpsInput.Value;
        _playback = new FramePlaybackEngine(
            () => Volatile.Read(ref _targetFps),
            action =>
            {
                if (!IsDisposed && IsHandleCreated) BeginInvoke(action);
            },
            PresentPreparedFrame,
            HandlePlaybackError);
        WireEvents();
        UpdateModeControls();
    }

    private void BuildInterface()
    {
        _settingsPanel.Dock = DockStyle.Left;
        _settingsPanel.Width = 365;
        _settingsPanel.Padding = new Padding(22);
        _settingsPanel.BackColor = Color.White;
        _settingsPanel.AutoScroll = true;
        Controls.Add(_qrDisplay);
        Controls.Add(_settingsPanel);

        var title = new Label
        {
            Text = "离线二维码发送器 V1.7.1",
            Font = new Font("Microsoft YaHei UI", 18, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(22, 22)
        };
        _settingsPanel.Controls.Add(title);

        var subtitle = new Label
        {
            Text = "手机校验成功后再停止发送",
            ForeColor = Color.FromArgb(88, 96, 110),
            AutoSize = true,
            Location = new Point(25, 62)
        };
        _settingsPanel.Controls.Add(subtitle);

        _textMode.Text = "发送文字";
        _textMode.Checked = true;
        _textMode.Location = new Point(25, 102);
        _textMode.AutoSize = true;
        _settingsPanel.Controls.Add(_textMode);

        _fileMode.Text = "发送文件";
        _fileMode.Location = new Point(140, 102);
        _fileMode.AutoSize = true;
        _settingsPanel.Controls.Add(_fileMode);

        _textInput.Multiline = true;
        _textInput.ScrollBars = ScrollBars.Vertical;
        _textInput.PlaceholderText = "在这里输入要发送的文字……";
        _textInput.Text = "你好，这是一次离线二维码传输测试。";
        _textInput.Location = new Point(25, 136);
        _textInput.Size = new Size(315, 150);
        _textInput.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        _settingsPanel.Controls.Add(_textInput);

        _filePath.Location = new Point(25, 136);
        _filePath.Size = new Size(222, 30);
        _filePath.ReadOnly = true;
        _filePath.PlaceholderText = "请选择文件";
        _settingsPanel.Controls.Add(_filePath);

        _browseButton.Text = "浏览…";
        _browseButton.Location = new Point(253, 134);
        _browseButton.Size = new Size(87, 33);
        _settingsPanel.Controls.Add(_browseButton);

        var fpsLabel = CreateLabel("速度（帧/秒）", 25, 310);
        _settingsPanel.Controls.Add(fpsLabel);
        _fpsInput.Location = new Point(210, 304);
        _fpsInput.Size = new Size(130, 30);
        _fpsInput.Minimum = 2;
        _fpsInput.Maximum = 60;
        _fpsInput.Value = 15;
        _settingsPanel.Controls.Add(_fpsInput);

        _fpsStatus.Text = "目标 15 FPS · 实际 — · 帧间隔 —";
        _fpsStatus.ForeColor = Color.FromArgb(46, 125, 112);
        _fpsStatus.Font = new Font("Microsoft YaHei UI", 8.5f, FontStyle.Bold);
        _fpsStatus.Location = new Point(25, 334);
        _fpsStatus.Size = new Size(315, 19);
        _settingsPanel.Controls.Add(_fpsStatus);

        var chunkLabel = CreateLabel("每帧数据（字节）", 25, 353);
        _settingsPanel.Controls.Add(chunkLabel);
        _chunkInput.Location = new Point(210, 347);
        _chunkInput.Size = new Size(130, 30);
        _chunkInput.Minimum = 100;
        _chunkInput.Maximum = 2800;
        _chunkInput.Increment = 128;
        _chunkInput.Value = 2800;
        _settingsPanel.Controls.Add(_chunkInput);

        var scaleLabel = CreateLabel("二维码尺寸（%）", 25, 396);
        _settingsPanel.Controls.Add(scaleLabel);
        _qrScaleInput.Location = new Point(210, 390);
        _qrScaleInput.Size = new Size(130, 30);
        _qrScaleInput.Minimum = 30;
        _qrScaleInput.Maximum = 150;
        _qrScaleInput.Increment = 5;
        _qrScaleInput.Value = 100;
        _settingsPanel.Controls.Add(_qrScaleInput);

        var tip = new Label
        {
            Text = "V1.7.1 首次元数据停留0.5秒；之后每64个数据帧循环发送一次元数据（停留0.1秒）。二维码同尺寸，数据帧最高60FPS。",
            ForeColor = Color.FromArgb(88, 96, 110),
            Location = new Point(25, 432),
            Size = new Size(315, 50)
        };
        _settingsPanel.Controls.Add(tip);

        _startButton.Text = "开始发送";
        _startButton.Location = new Point(25, 486);
        _startButton.Size = new Size(150, 42);
        _startButton.BackColor = Color.FromArgb(30, 102, 245);
        _startButton.ForeColor = Color.White;
        _startButton.FlatStyle = FlatStyle.Flat;
        _startButton.FlatAppearance.BorderSize = 0;
        _settingsPanel.Controls.Add(_startButton);

        _pauseButton.Text = "暂停";
        _pauseButton.Location = new Point(190, 486);
        _pauseButton.Size = new Size(72, 42);
        _pauseButton.Enabled = false;
        _settingsPanel.Controls.Add(_pauseButton);

        _stopButton.Text = "停止";
        _stopButton.Location = new Point(268, 486);
        _stopButton.Size = new Size(72, 42);
        _stopButton.Enabled = false;
        _settingsPanel.Controls.Add(_stopButton);

        _fullscreenButton.Text = "二维码全屏（F11）";
        _fullscreenButton.Location = new Point(25, 542);
        _fullscreenButton.Size = new Size(315, 36);
        _settingsPanel.Controls.Add(_fullscreenButton);

        var refillLabel = CreateLabel("指定补帧（手机编号）", 25, 598);
        _settingsPanel.Controls.Add(refillLabel);
        _refillFrameInput.Location = new Point(210, 592);
        _refillFrameInput.Size = new Size(130, 30);
        _refillFrameInput.Minimum = 1;
        _refillFrameInput.Maximum = 1;
        _refillFrameInput.Value = 1;
        _refillFrameInput.Enabled = false;
        _settingsPanel.Controls.Add(_refillFrameInput);

        _refillButton.Text = "指定补帧";
        _refillButton.Location = new Point(25, 634);
        _refillButton.Size = new Size(150, 38);
        _refillButton.Enabled = false;
        _settingsPanel.Controls.Add(_refillButton);

        _resumeLoopButton.Text = "恢复循环发送";
        _resumeLoopButton.Location = new Point(190, 634);
        _resumeLoopButton.Size = new Size(150, 38);
        _resumeLoopButton.Enabled = false;
        _settingsPanel.Controls.Add(_resumeLoopButton);

        _progress.Location = new Point(25, 690);
        _progress.Size = new Size(315, 18);
        _settingsPanel.Controls.Add(_progress);

        _status.Text = "等待开始";
        _status.Font = new Font("Microsoft YaHei UI", 10, FontStyle.Bold);
        _status.Location = new Point(25, 722);
        _status.Size = new Size(315, 26);
        _settingsPanel.Controls.Add(_status);

        _details.ForeColor = Color.FromArgb(88, 96, 110);
        _details.Location = new Point(25, 754);
        _details.Size = new Size(315, 100);
        _details.Text = "传输完成由手机端 SHA-256 校验决定。";
        _settingsPanel.Controls.Add(_details);
    }

    private static Label CreateLabel(string text, int x, int y)
    {
        return new Label
        {
            Text = text,
            AutoSize = true,
            Location = new Point(x, y)
        };
    }

    private void WireEvents()
    {
        _textMode.CheckedChanged += (_, _) => UpdateModeControls();
        _fileMode.CheckedChanged += (_, _) => UpdateModeControls();
        _browseButton.Click += (_, _) => BrowseForFile();
        _startButton.Click += async (_, _) => await StartTransferAsync();
        _pauseButton.Click += (_, _) => TogglePause();
        _stopButton.Click += (_, _) => StopTransfer();
        _fullscreenButton.Click += (_, _) => ToggleFullscreen();
        _refillButton.Click += async (_, _) => await ShowSpecificRefillFrameAsync();
        _resumeLoopButton.Click += (_, _) => ResumeLoopSending();
        _fpsInput.ValueChanged += (_, _) =>
        {
            Volatile.Write(ref _targetFps, (int)_fpsInput.Value);
            UpdateFpsStatus();
        };
        _qrScaleInput.ValueChanged += (_, _) => ApplyQrScale((int)_qrScaleInput.Value);
        KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.F11)
            {
                ToggleFullscreen();
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.Escape && _fullscreen)
            {
                ToggleFullscreen();
                e.Handled = true;
            }
            else if (e.KeyCode is Keys.Add or Keys.Oemplus)
            {
                AdjustQrScale(5);
                e.Handled = true;
            }
            else if (e.KeyCode is Keys.Subtract or Keys.OemMinus)
            {
                AdjustQrScale(-5);
                e.Handled = true;
            }
        };
        FormClosing += (_, _) => _playback.Dispose();
    }

    private void UpdateModeControls()
    {
        _textInput.Visible = _textMode.Checked;
        _filePath.Visible = _fileMode.Checked;
        _browseButton.Visible = _fileMode.Checked;
    }

    private void BrowseForFile()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "选择要发送的文件",
            CheckFileExists = true,
            Multiselect = false
        };

        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            _filePath.Text = dialog.FileName;
        }
    }

    private async Task StartTransferAsync()
    {
        try
        {
            SetBusyStatus("正在读取和计算校验值……");
            byte[] data;
            string fileName;
            string mimeType;

            if (_textMode.Checked)
            {
                data = Encoding.UTF8.GetBytes(_textInput.Text);
                fileName = $"文字-{DateTime.Now:yyyyMMdd-HHmmss}.txt";
                mimeType = "text/plain;charset=utf-8";
            }
            else
            {
                if (!File.Exists(_filePath.Text))
                {
                    throw new InvalidOperationException("请先选择一个存在的文件。");
                }

                var info = new FileInfo(_filePath.Text);
                if (info.Length > TransferSession.MaxFileBytes)
                {
                    throw new InvalidOperationException($"当前版本单次最多发送 {TransferSession.MaxFileBytes / 1024 / 1024} MiB。");
                }

                data = await File.ReadAllBytesAsync(_filePath.Text);
                fileName = info.Name;
                mimeType = MimeTypes.FromFileName(info.Name);
            }

            _session = TransferSession.Create(data, fileName, mimeType, (int)_chunkInput.Value);
            _roundSchedule = _session.GetScheduleForRound(1);
            _scheduleIndex = 0;
            _round = 1;
            _sentFrameCount = 0;
            _paused = false;
            _specificRefillMode = false;
            _progress.Minimum = 0;
            _progress.Maximum = _roundSchedule.Count;
            _progress.Value = 0;

            SetInputEnabled(false);
            _pauseButton.Enabled = true;
            _stopButton.Enabled = true;
            _refillFrameInput.Maximum = _session.TotalChunks;
            _refillFrameInput.Value = 1;
            _refillFrameInput.Enabled = true;
            _refillButton.Enabled = true;
            _resumeLoopButton.Enabled = false;
            _pauseButton.Text = "暂停";
            ResetPresentationMetrics();
            _status.Text = "正在预生成二维码帧……";
            _details.Text = "后台缓冲最多 8 帧；缓冲不足时保持当前画面，不建立待显示帧队列。";
            _playback.Start(_session, _round, _scheduleIndex);
        }
        catch (Exception ex)
        {
            _status.Text = "无法开始";
            _details.Text = ex.Message;
            SetInputEnabled(true);
            MessageBox.Show(this, ex.Message, "无法开始发送", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void PresentPreparedFrame(PreparedQrFrame frame)
    {
        if (_session is null || _paused || _specificRefillMode)
        {
            frame.Dispose();
            return;
        }

        var image = frame.TakeImage();
        frame.Dispose();
        _qrDisplay.SetImage(image);
        _qrDisplay.Update();
        if (_targetFps >= 30) _ = DwmFlush();

        _round = frame.NextRound;
        _scheduleIndex = frame.NextScheduleIndex;
        _currentEccText = frame.EccText;
        _sentFrameCount++;
        _progress.Value = frame.Round == 1
            ? Math.Min(frame.DisplayIndex, _progress.Maximum)
            : _progress.Maximum;
        var metadataDwellMilliseconds = frame.ScheduleEntry == -1
            ? (int)FramePlaybackEngine.GetPresentationMilliseconds(frame.ScheduleEntry, _targetFps, frame.Round, frame.DisplayIndex)
            : 0;
        RecordPresentation(frame.ScheduleEntry == -1, metadataDwellMilliseconds);

        var now = Stopwatch.GetTimestamp();
        if (_lastDetailsTicks != 0 && (now - _lastDetailsTicks) * 1000.0 / Stopwatch.Frequency < 100) return;
        _lastDetailsTicks = now;
        var frameName = frame.ScheduleEntry switch
        {
            -1 => "元数据",
            -2 => "轮次结束标记",
            _ => $"数据 {frame.ScheduleEntry + 1}/{_session.TotalChunks}"
        };
        var phase = frame.Round == 1 ? "首轮发送" : $"第 {frame.Round} 轮交错补发";
        var roundProgress = frame.Round == 1
            ? $"首轮进度：{frame.DisplayIndex}/{frame.ScheduleCount}"
            : $"首轮已完成　本轮补发：{frame.DisplayIndex}/{frame.ScheduleCount}";
        var estimatedFirstRound = FormatDuration(_session.Schedule.Count / (double)Math.Max(1, _targetFps));
        _status.Text = $"{phase}：{frameName}";
        var dwellStatus = frame.ScheduleEntry == -1 ? $"　元数据停留：{metadataDwellMilliseconds}ms" : string.Empty;
        _details.Text = $"文件：{_session.FileName}\r\n数据帧：{_session.TotalChunks}　预计首轮：{estimatedFirstRound}\r\n{roundProgress}　累计实际呈现：{_sentFrameCount} 帧\r\n目标：{_targetFps} FPS　实际：{_actualFps:F1} FPS{dwellStatus}　纠错：{_currentEccText}\r\n会话：{_session.SessionId}";
    }

    private void RecordPresentation(bool metadataHold, int metadataDwellMilliseconds)
    {
        var now = Stopwatch.GetTimestamp();
        if (metadataHold)
        {
            _lastPresentationTicks = 0;
            _fpsWindowStartTicks = 0;
            _fpsWindowFrames = 0;
            _actualFps = 0;
            _actualFrameIntervalMs = 0;
            _intervalWindowTotalMs = 0;
            _intervalWindowSamples = 0;
            _fpsStatus.Text = $"元数据停留 {metadataDwellMilliseconds}ms · 数据目标 {_targetFps} FPS";
            _fpsStatus.ForeColor = Color.FromArgb(46, 125, 112);
            return;
        }
        if (_lastPresentationTicks != 0)
        {
            _intervalWindowTotalMs += (now - _lastPresentationTicks) * 1000.0 / Stopwatch.Frequency;
            _intervalWindowSamples++;
        }
        _lastPresentationTicks = now;
        if (_fpsWindowStartTicks == 0) _fpsWindowStartTicks = now;
        _fpsWindowFrames++;
        var elapsed = (now - _fpsWindowStartTicks) / (double)Stopwatch.Frequency;
        if (elapsed < 0.5) return;

        _actualFps = _fpsWindowFrames / elapsed;
        _actualFrameIntervalMs = _intervalWindowSamples == 0 ? 0 : _intervalWindowTotalMs / _intervalWindowSamples;
        _fpsWindowStartTicks = now;
        _fpsWindowFrames = 0;
        _intervalWindowTotalMs = 0;
        _intervalWindowSamples = 0;
        UpdateFpsStatus();
    }

    private void ResetPresentationMetrics()
    {
        _lastPresentationTicks = 0;
        _fpsWindowStartTicks = 0;
        _fpsWindowFrames = 0;
        _actualFps = 0;
        _actualFrameIntervalMs = 0;
        _intervalWindowTotalMs = 0;
        _intervalWindowSamples = 0;
        _lastDetailsTicks = 0;
        UpdateFpsStatus();
    }

    private void UpdateFpsStatus()
    {
        var actual = _actualFps > 0 ? $"{_actualFps:F1}" : "—";
        var interval = _actualFrameIntervalMs > 0 ? $"{_actualFrameIntervalMs:F1}ms" : "—";
        _fpsStatus.Text = $"目标 {_targetFps} FPS · 实际 {actual} · 帧间隔 {interval}";
        _fpsStatus.ForeColor = _actualFps > 0 && _actualFps < _targetFps * 0.9
            ? Color.FromArgb(194, 94, 37)
            : Color.FromArgb(46, 125, 112);
    }

    private void HandlePlaybackError(Exception error)
    {
        _playback.Stop();
        _status.Text = "二维码预生成或显示失败";
        _details.Text = error.Message + "\r\n请降低每帧数据量或目标 FPS 后重试。";
        _pauseButton.Enabled = false;
    }

    private void TogglePause()
    {
        if (_session is null)
        {
            return;
        }

        _paused = !_paused;
        _pauseButton.Text = _paused ? "继续" : "暂停";
        _status.Text = _paused ? "已暂停" : "继续发送";
        if (_paused)
        {
            _playback.Stop();
        }
        else
        {
            ResetPresentationMetrics();
            _playback.Start(_session, _round, _scheduleIndex);
        }
    }

    private async Task ShowSpecificRefillFrameAsync()
    {
        if (_session is null)
        {
            return;
        }

        try
        {
            var session = _session;
            var displayNumber = decimal.ToInt32(_refillFrameInput.Value);
            var dataIndex = displayNumber - 1;
            _playback.Stop();
            _paused = false;
            _specificRefillMode = true;

            _status.Text = $"正在生成指定补帧 {displayNumber}……";
            var frame = await FramePlaybackEngine.PrepareSpecificAsync(session, dataIndex, Math.Max(1, _round));
            if (_session != session || !_specificRefillMode)
            {
                frame.Dispose();
                return;
            }
            var image = frame.TakeImage();
            _currentEccText = frame.EccText;
            frame.Dispose();
            _qrDisplay.SetImage(image);
            _qrDisplay.Update();
            _sentFrameCount++;

            _pauseButton.Enabled = false;
            _pauseButton.Text = "暂停";
            _resumeLoopButton.Enabled = true;
            _status.Text = $"指定补帧：第 {displayNumber} 帧";
            _details.Text = $"文件：{_session.FileName}\r\n正在保持显示数据帧 {displayNumber}/{_session.TotalChunks}　纠错：{_currentEccText}\r\n请让手机成功收到后，再输入下一编号；完成后点击“恢复循环发送”。\r\n累计显示：{_sentFrameCount} 帧　会话：{_session.SessionId}";
        }
        catch (Exception ex)
        {
            _status.Text = "指定补帧失败";
            _details.Text = ex.Message;
        }
    }

    private void ResumeLoopSending()
    {
        if (_session is null)
        {
            return;
        }

        _specificRefillMode = false;
        _paused = false;
        _pauseButton.Enabled = true;
        _pauseButton.Text = "暂停";
        _resumeLoopButton.Enabled = false;
        _status.Text = "已恢复循环发送";
        ResetPresentationMetrics();
        _playback.Start(_session, _round, _scheduleIndex);
    }

    private void StopTransfer()
    {
        _playback.Stop();
        _session = null;
        _roundSchedule = Array.Empty<int>();
        _scheduleIndex = 0;
        _round = 0;
        _sentFrameCount = 0;
        _paused = false;
        _specificRefillMode = false;
        _qrDisplay.ClearImage();
        _progress.Value = 0;
        _status.Text = "已停止";
        _details.Text = "手机校验成功后可安全停止。";
        _pauseButton.Enabled = false;
        _stopButton.Enabled = false;
        _refillFrameInput.Enabled = false;
        _refillFrameInput.Maximum = 1;
        _refillFrameInput.Value = 1;
        _refillButton.Enabled = false;
        _resumeLoopButton.Enabled = false;
        _pauseButton.Text = "暂停";
        ResetPresentationMetrics();
        SetInputEnabled(true);
    }

    private void SetBusyStatus(string message)
    {
        _status.Text = message;
        _details.Text = string.Empty;
        _startButton.Enabled = false;
        Refresh();
    }

    private static string FormatDuration(double seconds)
    {
        var duration = TimeSpan.FromSeconds(Math.Max(0, Math.Ceiling(seconds)));
        if (duration.TotalHours >= 1)
        {
            return $"{(int)duration.TotalHours}小时{duration.Minutes}分";
        }

        if (duration.TotalMinutes >= 1)
        {
            return $"{(int)duration.TotalMinutes}分{duration.Seconds}秒";
        }

        return $"{duration.Seconds}秒";
    }

    private void SetInputEnabled(bool enabled)
    {
        _textMode.Enabled = enabled;
        _fileMode.Enabled = enabled;
        _textInput.Enabled = enabled;
        _filePath.Enabled = enabled;
        _browseButton.Enabled = enabled;
        _chunkInput.Enabled = enabled;
        _startButton.Enabled = enabled;
    }

    private void ToggleFullscreen()
    {
        if (!_fullscreen)
        {
            _previousBorder = FormBorderStyle;
            _previousBounds = Bounds;
            _settingsPanel.Visible = false;
            FormBorderStyle = FormBorderStyle.None;
            WindowState = FormWindowState.Normal;
            Bounds = Screen.FromControl(this).Bounds;
            TopMost = true;
            _fullscreen = true;
        }
        else
        {
            TopMost = false;
            FormBorderStyle = _previousBorder;
            Bounds = _previousBounds;
            _settingsPanel.Visible = true;
            _fullscreen = false;
        }
    }

    private void AdjustQrScale(int delta)
    {
        var next = Math.Clamp((int)_qrScaleInput.Value + delta, (int)_qrScaleInput.Minimum, (int)_qrScaleInput.Maximum);
        _qrScaleInput.Value = next;
    }

    private void ApplyQrScale(int percent)
    {
        _qrDisplay.ScalePercent = Math.Min(100, percent);
        if (_fullscreen) return;

        if (percent <= 100)
        {
            if (_scaleWindowExpanded)
            {
                Bounds = _scaleBaseBounds;
                _scaleWindowExpanded = false;
            }
            return;
        }

        if (!_scaleWindowExpanded)
        {
            _scaleBaseBounds = Bounds;
            _scaleWindowExpanded = true;
        }

        var workingArea = Screen.FromControl(this).WorkingArea;
        var baseQrSide = Math.Max(1, Math.Min(
            _scaleBaseBounds.Width - _settingsPanel.Width - 48,
            _scaleBaseBounds.Height - 48));
        var targetQrSide = (int)Math.Round(baseQrSide * percent / 100.0);
        var growth = Math.Max(0, targetQrSide - baseQrSide);
        var desiredWidth = Math.Min(workingArea.Width, _scaleBaseBounds.Width + growth);
        var desiredHeight = Math.Min(workingArea.Height, _scaleBaseBounds.Height + growth);
        var centerX = _scaleBaseBounds.Left + _scaleBaseBounds.Width / 2;
        var centerY = _scaleBaseBounds.Top + _scaleBaseBounds.Height / 2;
        var left = Math.Clamp(centerX - desiredWidth / 2, workingArea.Left, workingArea.Right - desiredWidth);
        var top = Math.Clamp(centerY - desiredHeight / 2, workingArea.Top, workingArea.Bottom - desiredHeight);
        Bounds = new Rectangle(left, top, desiredWidth, desiredHeight);
    }

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmFlush();
}
