package com.furkanfidanoglu.cruxaisummarize.util.helpers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ImageUtil {

    // 🔧 ULTRA AYARLAR:
    // 1920px: Metin okuma (OCR) için en ideal çözünürlük (Full HD genişliği).
    // Daha düşüğü yazıları bozar, daha yükseği gereksiz yavaştır.
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1920;

    private static final int COMPRESSION_QUALITY = 85;

    /**
     * Devasa resimleri (50MB+) bile RAM'i patlatmadan işler.
     * Hem hızlıdır hem de Gemini için en net görüntüyü üretir.
     */
    public static byte[] processImage(byte[] originalBytes) {
        if (originalBytes == null) return null;

        ByteArrayOutputStream outputStream = null;
        Bitmap finalBitmap = null;
        Bitmap tempBitmap = null;

        try {
            // 1. YÖN BİLGİSİNİ AL (Yan çekilmiş fotolar düzelmeli)
            int orientation = getOrientation(originalBytes);

            // 2. BOYUTLARI ÖLÇ (Resmi RAM'e yüklemeden sadece kenarlarını oku)
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.length, options);

            // 3. OPTİMİZE ÖRNEKLEME (InSampleSize hesapla)
            options.inSampleSize = calculateInSampleSize(options, MAX_WIDTH, MAX_HEIGHT);

            // 4. GÜVENLİ YÜKLEME AYARLARI
            options.inJustDecodeBounds = false;
            // 🔥 KRİTİK NOKTA: RGB_565 kullanarak RAM kullanımını yarıya indiriyoruz.
            // 50MB dosyalarda "Out Of Memory" hatasını engelleyen sihir budur.
            // İnsan gözü farkı anlamaz ama metinler hala nettir.
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            // 5. RESMİ YÜKLE
            tempBitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.length, options);

            if (tempBitmap == null) return originalBytes; // Yükleme başarısızsa orijinali dön

            // 6. DÖNDÜR (Varsa)
            finalBitmap = rotateBitmap(tempBitmap, orientation);

            // Memory Leak önlemi: tempBitmap artık gereksizse sil.
            if (finalBitmap != tempBitmap) {
                tempBitmap.recycle();
            }

            // 7. SIKIŞTIR VE ÇIKTI ÜRET
            outputStream = new ByteArrayOutputStream();
            // JPEG formatı metin içeren fotolar için en performanslısıdır.
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, outputStream);

            return outputStream.toByteArray();

        } catch (OutOfMemoryError e) {
            // Eğer telefon çok eskiyse ve yine de hafıza bittiyse;
            // Sistemi temizle ve risk almamak için orijinal veriyi (veya null) dön.
            System.gc();
            e.printStackTrace();
            return originalBytes;
        } catch (Exception e) {
            e.printStackTrace();
            return originalBytes;
        } finally {
            try {
                if (outputStream != null) outputStream.close();
                // Bitmap işimiz bitti, RAM'den hemen atalım.
                if (finalBitmap != null && !finalBitmap.isRecycled()) {
                    finalBitmap.recycle();
                }
                // Çöp toplayıcıya "Müsait olduğunda gel" sinyali çakalım.
                System.gc();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // ---------------- YARDIMCI METODLAR ----------------

    private static int getOrientation(byte[] data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {
            ExifInterface exif = new ExifInterface(inputStream);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        boolean needsRotation = false;

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                needsRotation = true;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                needsRotation = true;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                needsRotation = true;
                break;
        }

        if (!needsRotation) {
            return bitmap;
        }

        try {
            // Yeni döndürülmüş bitmap oluştur
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (OutOfMemoryError e) {
            // Döndürürken hafıza yetmezse orijinal (yamuk) haliyle devam et, hiç yoktan iyidir.
            return bitmap;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Resmin ham boyutları
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Hedef boyuta yaklaşana kadar 2'ye bölerek küçült (2, 4, 8, 16...)
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}