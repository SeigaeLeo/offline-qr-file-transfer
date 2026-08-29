using System.Drawing.Drawing2D;

namespace QrTransferSender;

public sealed class QrDisplayControl : Control
{
    private Image? _image;
    private int _scalePercent = 78;

    public QrDisplayControl()
    {
        DoubleBuffered = true;
        BackColor = Color.White;
        Dock = DockStyle.Fill;
    }

    public int ScalePercent
    {
        get => _scalePercent;
        set
        {
            _scalePercent = Math.Clamp(value, 30, 150);
            UpdateRasterMetrics();
            Invalidate();
        }
    }

    public int PixelsPerModule { get; private set; }

    public bool IsIntegerModuleScale => PixelsPerModule > 0;

    public void SetImage(Image image)
    {
        var previous = _image;
        _image = image;
        previous?.Dispose();
        UpdateRasterMetrics();
        Invalidate();
    }

    public void ClearImage()
    {
        var previous = _image;
        _image = null;
        PixelsPerModule = 0;
        previous?.Dispose();
        Invalidate();
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        UpdateRasterMetrics();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        if (_image is null)
        {
            using var brush = new SolidBrush(Color.FromArgb(90, 98, 112));
            using var font = new Font(Font.FontFamily, 18, FontStyle.Regular);
            const string message = "选择内容后开始发送";
            var size = e.Graphics.MeasureString(message, font);
            e.Graphics.DrawString(message, font, brush, (Width - size.Width) / 2, (Height - size.Height) / 2);
            return;
        }

        var padding = Math.Max(8, (int)Math.Round(24 * DeviceDpi / 96.0));
        var availableWidth = Math.Max(1, Width - padding * 2);
        var availableHeight = Math.Max(1, Height - padding * 2);
        var requestedSide = CalculateRequestedSide(Math.Min(availableWidth, availableHeight), ScalePercent);
        var side = CalculateIntegerScaledSide(_image.Width, requestedSide, out var pixelsPerModule);
        PixelsPerModule = pixelsPerModule;
        var x = (Width - side) / 2;
        var y = (Height - side) / 2;

        e.Graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
        e.Graphics.SmoothingMode = SmoothingMode.None;
        e.Graphics.PixelOffsetMode = PixelOffsetMode.Half;
        e.Graphics.CompositingQuality = CompositingQuality.HighSpeed;
        e.Graphics.CompositingMode = CompositingMode.SourceCopy;
        e.Graphics.DrawImage(_image, new Rectangle(x, y, side, side));
    }

    public static int CalculateIntegerScaledSide(int sourceSide, int requestedSide, out int pixelsPerModule)
    {
        if (sourceSide <= 0 || requestedSide <= 0)
        {
            pixelsPerModule = 0;
            return Math.Max(1, requestedSide);
        }

        pixelsPerModule = requestedSide / sourceSide;
        if (pixelsPerModule < 1)
        {
            pixelsPerModule = 0;
            return requestedSide;
        }
        return sourceSide * pixelsPerModule;
    }

    public static int CalculateRequestedSide(int availableSide, int scalePercent)
    {
        return Math.Max(1, Math.Max(1, availableSide) * Math.Clamp(scalePercent, 30, 150) / 150);
    }

    private void UpdateRasterMetrics()
    {
        if (_image is null)
        {
            PixelsPerModule = 0;
            return;
        }
        var padding = Math.Max(8, (int)Math.Round(24 * DeviceDpi / 96.0));
        var availableWidth = Math.Max(1, Width - padding * 2);
        var availableHeight = Math.Max(1, Height - padding * 2);
        var requestedSide = CalculateRequestedSide(Math.Min(availableWidth, availableHeight), ScalePercent);
        _ = CalculateIntegerScaledSide(_image.Width, requestedSide, out var pixelsPerModule);
        PixelsPerModule = pixelsPerModule;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _image?.Dispose();
        }

        base.Dispose(disposing);
    }
}
