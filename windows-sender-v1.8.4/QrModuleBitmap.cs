using QRCoder;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace QrTransferSender;

public static class QrModuleBitmap
{
    public static Bitmap Render(QRCodeData qrData)
    {
        var side = qrData.ModuleMatrix.Count;
        var bitmap = new Bitmap(side, side, PixelFormat.Format1bppIndexed);
        var palette = bitmap.Palette;
        palette.Entries[0] = Color.Black;
        palette.Entries[1] = Color.White;
        bitmap.Palette = palette;

        var bounds = new Rectangle(0, 0, side, side);
        try
        {
            var locked = bitmap.LockBits(bounds, ImageLockMode.WriteOnly, PixelFormat.Format1bppIndexed);
            try
            {
                var bytes = new byte[locked.Stride * side];
                Array.Fill(bytes, (byte)0xFF);
                for (var y = 0; y < side; y++)
                {
                    var row = qrData.ModuleMatrix[y];
                    var rowOffset = y * locked.Stride;
                    for (var x = 0; x < side; x++)
                    {
                        if (row[x]) bytes[rowOffset + x / 8] &= (byte)~(0x80 >> (x & 7));
                    }
                }
                Marshal.Copy(bytes, 0, locked.Scan0, bytes.Length);
            }
            finally
            {
                bitmap.UnlockBits(locked);
            }
        }
        catch
        {
            bitmap.Dispose();
            throw;
        }
        return bitmap;
    }
}
