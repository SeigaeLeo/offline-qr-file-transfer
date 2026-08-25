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
            _scalePercent = Math.Clamp(value, 30, 100);
            Invalidate();
        }
    }

    public void SetImage(Image image)
    {
        var previous = _image;
        _image = image;
        previous?.Dispose();
        Invalidate();
    }

    public void ClearImage()
    {
        var previous = _image;
        _image = null;
        previous?.Dispose();
        Invalidate();
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

        const int padding = 24;
        var availableWidth = Math.Max(1, Width - padding * 2);
        var availableHeight = Math.Max(1, Height - padding * 2);
        var requestedSide = Math.Max(1, Math.Min(availableWidth, availableHeight) * ScalePercent / 100);
        var side = requestedSide;
        var x = (Width - side) / 2;
        var y = (Height - side) / 2;

        e.Graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
        e.Graphics.SmoothingMode = SmoothingMode.None;
        e.Graphics.PixelOffsetMode = PixelOffsetMode.Half;
        e.Graphics.CompositingQuality = CompositingQuality.HighSpeed;
        e.Graphics.DrawImage(_image, new Rectangle(x, y, side, side));
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
