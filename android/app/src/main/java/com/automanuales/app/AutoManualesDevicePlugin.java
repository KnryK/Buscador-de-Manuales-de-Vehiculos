package com.automanuales.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "AutoManualesDevice")
public class AutoManualesDevicePlugin extends Plugin {

    private static final String TAG = "AutoManualesDevice";

    private Uri pendingPhotoUri;

    @PluginMethod
    public void takePhoto(PluginCall call) {
        Log.d(TAG, "takePhoto() RECIBIDO");
        final Activity activity = getActivity();

        if (activity == null) {
            call.reject("No hay una actividad de Android disponible.");
            return;
        }

        /*
         * Android 10+ permite insertar primero la foto en MediaStore y
         * entregarle esa URI a la aplicación de cámara.
         */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject(
                "El guardado directo en Pictures/AutoManuales requiere Android 10 o superior."
            );
            return;
        }

        final ContentResolver resolver = activity.getContentResolver();

        String requestedName = call.getString(
            "fileName",
            "AutoManuales_" + System.currentTimeMillis() + ".jpg"
        );

        final String fileName = sanitizeFileName(requestedName);

        ContentValues values = new ContentValues();
        values.put(
            MediaStore.Images.Media.DISPLAY_NAME,
            fileName
        );
        values.put(
            MediaStore.Images.Media.MIME_TYPE,
            "image/jpeg"
        );
        values.put(
            MediaStore.Images.Media.DATE_TAKEN,
            System.currentTimeMillis()
        );
        values.put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/AutoManuales"
        );
        values.put(
            MediaStore.Images.Media.IS_PENDING,
            1
        );

        final Uri outputUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        );

        if (outputUri == null) {
            call.reject(
                "Android no pudo crear el destino de la fotografía en la galería."
            );
            return;
        }

        pendingPhotoUri = outputUri;
        Log.d(TAG, "takePhoto() carpeta: Pictures/AutoManuales");
        Log.d(TAG, "takePhoto() MediaStore URI creada: " + outputUri);

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        intent.setClipData(
            ClipData.newRawUri("AutoManualesPhoto", outputUri)
        );
        Log.d(TAG, "takePhoto() abriendo ACTION_IMAGE_CAPTURE");

        try {
            startActivityForResult(
                call,
                intent,
                "cameraResult"
            );
        } catch (Exception error) {
            pendingPhotoUri = null;

            try {
                resolver.delete(outputUri, null, null);
            } catch (Exception ignored) {}

            call.reject(
                "No se pudo abrir la cámara de Android.",
                error.getMessage()
            );
        }
    }

    @ActivityCallback
    private void cameraResult(
        PluginCall call,
        ActivityResult result
    ) {
        final Uri outputUri = pendingPhotoUri;
        pendingPhotoUri = null;
        Log.d(TAG, "cameraResult() recibido. resultCode=" + result.getResultCode()
                + " uri=" + outputUri);

        Activity activity = getActivity();

        if (call == null) {
            return;
        }

        if (activity == null || outputUri == null) {
            call.reject(
                "No se pudo recuperar el resultado de la cámara."
            );
            return;
        }

        ContentResolver resolver = activity.getContentResolver();

        if (result.getResultCode() == Activity.RESULT_OK) {
            try {
                /*
                 * Estrategia en tres pasos:
                 *  1. Leer los bytes crudos del archivo pendiente.
                 *  2. Generar un THUMBNAIL pequeño (≤800 px) para la vista
                 *     previa en JS — el base64 resultante es ~100 KB, dentro
                 *     del límite del bridge de Capacitor.
                 *  3. Publicar la foto en tamaño completo (IS_PENDING = 0)
                 *     en la galería.
                 */

                // Paso 1: leer bytes crudos
                byte[] rawBytes;
                try (InputStream is = resolver.openInputStream(outputUri)) {
                    if (is == null) {
                        throw new IOException("No se pudo abrir el archivo de la cámara.");
                    }
                    rawBytes = readStreamToBytes(is);
                }

                // Paso 2: calcular inSampleSize para thumbnail ≤ 800 px
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, bounds);

                int sampleSize = 1;
                int w = bounds.outWidth;
                int h = bounds.outHeight;
                while (w / sampleSize > 800 || h / sampleSize > 800) {
                    sampleSize *= 2;
                }

                BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                decodeOpts.inSampleSize = sampleSize;
                Bitmap thumb = BitmapFactory.decodeByteArray(
                    rawBytes, 0, rawBytes.length, decodeOpts
                );

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                thumb.compress(Bitmap.CompressFormat.JPEG, 75, bos);
                thumb.recycle();
                String thumbnailBase64 = Base64.encodeToString(
                    bos.toByteArray(), Base64.NO_WRAP
                );

                // Paso 3: publicar la foto en tamaño completo en la galería
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(outputUri, done, null, null);

                JSObject response = new JSObject();
                response.put("imageBase64", "data:image/jpeg;base64," + thumbnailBase64);
                response.put("folder", "Pictures/AutoManuales");
                response.put("uri", outputUri.toString());

                call.resolve(response);

            } catch (Exception error) {
                // Si algo falla, intentar publicar de todas formas para no perder la foto
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(outputUri, done, null, null);
                } catch (Exception ignored) {}

                call.reject(
                    "La foto se guardó en la galería, pero no se pudo generar la vista previa.",
                    error.getMessage()
                );
            }
        } else {
            try {
                resolver.delete(
                    outputUri,
                    null,
                    null
                );
            } catch (Exception ignored) {}

            call.reject("Captura cancelada.");
        }
    }

    private static byte[] readStreamToBytes(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int len;
        while ((len = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, len);
        }
        return buffer.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // saveImage — guarda un JPEG (base64) enviado desde JavaScript en la
    //             galería. Acepta folderName opcional (default AutoManualesFoto).
    // ─────────────────────────────────────────────────────────────────────────
    @PluginMethod
    public void saveImage(PluginCall call) {
        Log.d(TAG, "saveImage() RECIBIDO");

        String base64 = call.getString("imageBase64");
        String requestedName = call.getString(
            "fileName",
            "AutoManualesFoto_" + System.currentTimeMillis() + ".jpg"
        );
        String folderName = call.getString("folderName", "AutoManualesFoto");

        if (base64 == null || base64.isEmpty()) {
            call.reject("No se recibió la imagen.");
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject(
                "El guardado en galería requiere Android 10 o superior."
            );
            return;
        }

        final String fileName = sanitizeFileName(requestedName);

        new Thread(() -> {
            try {
                // Quitar el encabezado "data:image/jpeg;base64," si viene incluido
                String cleanBase64 = base64.contains(",")
                    ? base64.substring(base64.indexOf(',') + 1)
                    : base64;

                byte[] imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT);

                Activity activity = getActivity();
                if (activity == null) {
                    call.reject("No hay una actividad Android disponible.");
                    return;
                }

                ContentResolver resolver = activity.getContentResolver();

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE,    "image/jpeg");
                values.put(MediaStore.Images.Media.DATE_TAKEN,   System.currentTimeMillis());
                values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + folderName
                );
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                );

                if (uri == null) {
                    call.reject("Android no pudo crear el destino en la galería.");
                    return;
                }

                Log.d(TAG, "saveImage() URI creada: " + uri);

                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("OutputStream nulo al abrir la URI.");
                    }
                    out.write(imageBytes);
                    out.flush();
                }

                // Publicar la imagen (IS_PENDING = 0)
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);

                Log.d(TAG, "saveImage() guardado correctamente en Pictures/" + folderName);

                JSObject result = new JSObject();
                result.put("uri",      uri.toString());
                result.put("folder",   "Pictures/" + folderName);
                result.put("fileName", fileName);
                call.resolve(result);

            } catch (Exception e) {
                Log.e(TAG, "saveImage() error", e);
                call.reject("Error al guardar la imagen: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void printPdf(PluginCall call) {
        String base64 = call.getString("pdfBase64");
        String requestedJobName = call.getString("jobName");

        if (base64 == null || base64.isEmpty()) {
            call.reject("No se recibió el PDF para imprimir.");
            return;
        }

        final String jobName =
            (requestedJobName == null || requestedJobName.trim().isEmpty())
                ? "Manual AutoManuales"
                : requestedJobName.trim();

        Activity activity = getActivity();

        if (activity == null) {
            call.reject("No hay una actividad Android disponible.");
            return;
        }

        if (!activity.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_PRINTING)) {
            call.reject(
                "Este dispositivo no tiene disponible el sistema de impresión de Android."
            );
            return;
        }

        final byte[] pdfBytes;

        try {
            pdfBytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("El PDF recibido no es válido.");
            return;
        }

        if (pdfBytes.length < 5) {
            call.reject("El PDF recibido está vacío.");
            return;
        }

        final File tempPdf;

        try {
            tempPdf = File.createTempFile(
                "automanuales_print_",
                ".pdf",
                activity.getCacheDir()
            );

            try (FileOutputStream output =
                     new FileOutputStream(tempPdf)) {

                output.write(pdfBytes);
                output.flush();
            }

        } catch (IOException e) {
            call.reject("No se pudo preparar el PDF para impresión.");
            return;
        }

        final Context printContext = activity.getApplicationContext();

        activity.runOnUiThread(() -> {
            try {
                PrintManager printManager =
                    (PrintManager) activity.getSystemService(
                        Context.PRINT_SERVICE
                    );

                if (printManager == null) {
                    deleteQuietly(tempPdf);
                    call.reject(
                        "El servicio de impresión de Android no está disponible."
                    );
                    return;
                }

                PdfPrintAdapter adapter = new PdfPrintAdapter(
                    printContext,
                    tempPdf,
                    jobName,
                    () -> deleteQuietly(tempPdf)
                );

                /*
                 * Preferimos abrir el diálogo de impresión en horizontal.
                 * PrintManager.print() usa estos atributos como configuración
                 * inicial del trabajo.
                 */
                PrintAttributes defaultPrintAttributes =
                    new PrintAttributes.Builder()
                        .setMediaSize(
                            new PrintAttributes.MediaSize(
                            "A4_LANDSCAPE",
                            "A4",
                            11693,
                            8268
                        )
                        )
                        .setResolution(
                            new PrintAttributes.Resolution(
                                "automanuales_print",
                                "AutoManuales",
                                300,
                                300
                            )
                        )
                        .setMinMargins(
                            new PrintAttributes.Margins(
                                0,
                                0,
                                0,
                                0
                            )
                        )
                        .build();

                printManager.print(
                    jobName,
                    adapter,
                    defaultPrintAttributes
                );

                /*
                 * PrintManager.print() abre inmediatamente la interfaz del
                 * sistema. No esperamos la selección del usuario aquí.
                 *
                 * Android no expone una API pública para seleccionar por código
                 * el modo "Páginas: Rango de..." como selección inicial.
                 * La lista de páginas sí queda preparada con pageCount y
                 * onWrite() respeta cualquier rango que el usuario seleccione.
                 */
                call.resolve();

            } catch (Exception e) {
                Log.e(TAG, "No se pudo abrir la interfaz de impresión", e);
                deleteQuietly(tempPdf);
                call.reject(
                    "No se pudo abrir la impresión de Android."
                );
            }
        });
    }

    private static String sanitizeFileName(String value) {
        String safe = value == null ? "" : value.trim();

        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        safe = safe.replaceAll("\\s+", "_");

        if (safe.isEmpty()) {
            safe = "AutoManuales";
        }

        if (!safe.toLowerCase().endsWith(".jpg")) {
            safe += ".jpg";
        }

        return safe;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            try {
                file.delete();
            } catch (Exception ignored) {}
        }
    }

    private static class PdfPrintAdapter extends PrintDocumentAdapter {

        private final Context context;
        private final File sourceFile;
        private final String jobName;
        private final Runnable cleanup;

        private PdfRenderer renderer;
        private ParcelFileDescriptor sourcePfd;
        private PrintAttributes printAttributes;
        private int pageCount;

        private final ExecutorService printExecutor =
            Executors.newSingleThreadExecutor();

        PdfPrintAdapter(
            Context context,
            File sourceFile,
            String jobName,
            Runnable cleanup
        ) {
            this.context = context;
            this.sourceFile = sourceFile;
            this.jobName = jobName;
            this.cleanup = cleanup;
        }

        @Override
        public void onStart() {
            try {
                sourcePfd = ParcelFileDescriptor.open(
                    sourceFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
                );

                renderer = new PdfRenderer(sourcePfd);
                pageCount = renderer.getPageCount();

            } catch (Exception e) {
                Log.e(TAG, "No se pudo abrir el PDF para imprimir", e);
            }
        }

        @Override
        public void onLayout(
            PrintAttributes oldAttributes,
            PrintAttributes newAttributes,
            CancellationSignal cancellationSignal,
            LayoutResultCallback callback,
            Bundle extras
        ) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }

            printAttributes =
                newAttributes != null ? newAttributes : oldAttributes;

            if (renderer == null || printAttributes == null) {
                callback.onLayoutFailed(
                    "No se pudo preparar el documento PDF."
                );
                return;
            }

            PrintDocumentInfo info =
                new PrintDocumentInfo.Builder(jobName)
                    .setContentType(
                        PrintDocumentInfo.CONTENT_TYPE_DOCUMENT
                    )
                    .setPageCount(pageCount)
                    .build();

            boolean changed =
                oldAttributes == null ||
                !oldAttributes.equals(printAttributes);

            callback.onLayoutFinished(info, changed);
        }

        @Override
        public void onWrite(
            PageRange[] pages,
            ParcelFileDescriptor destination,
            CancellationSignal cancellationSignal,
            WriteResultCallback callback
        ) {
            if (renderer == null || printAttributes == null) {
                callback.onWriteFailed(
                    "El documento no está listo para imprimir."
                );
                return;
            }

            printExecutor.execute(() -> {

                PrintedPdfDocument output = null;

                try {
                    output = new PrintedPdfDocument(
                        context,
                        printAttributes
                    );

                    int outputPageIndex = 0;

                    for (PageRange range : pages) {

                        int start = Math.max(0, range.getStart());
                        int end = Math.min(
                            pageCount - 1,
                            range.getEnd()
                        );

                        if (start > end) {
                            continue;
                        }

                        for (
                            int sourceIndex = start;
                            sourceIndex <= end;
                            sourceIndex++
                        ) {

                            if (cancellationSignal.isCanceled()) {
                                callback.onWriteCancelled();
                                return;
                            }

                            PdfRenderer.Page sourcePage =
                                renderer.openPage(sourceIndex);

                            android.graphics.pdf.PdfDocument.Page outPage =
                                output.startPage(outputPageIndex);

                            Rect contentRect =
                                outPage.getInfo().getContentRect();

                            int contentWidth =
                                Math.max(1, contentRect.width());
                            int contentHeight =
                                Math.max(1, contentRect.height());

                            float sourceAspect =
                                (float) sourcePage.getWidth() /
                                Math.max(
                                    1,
                                    sourcePage.getHeight()
                                );

                            float targetAspect =
                                (float) contentWidth /
                                contentHeight;

                            final int renderDensity = 2;

                            int bitmapWidth;
                            int bitmapHeight;

                            if (sourceAspect > targetAspect) {
                                bitmapWidth =
                                    contentWidth * renderDensity;

                                bitmapHeight = Math.max(
                                    1,
                                    Math.round(
                                        bitmapWidth /
                                        sourceAspect
                                    )
                                );

                            } else {
                                bitmapHeight =
                                    contentHeight * renderDensity;

                                bitmapWidth = Math.max(
                                    1,
                                    Math.round(
                                        bitmapHeight *
                                        sourceAspect
                                    )
                                );
                            }

                            Bitmap bitmap = Bitmap.createBitmap(
                                bitmapWidth,
                                bitmapHeight,
                                Bitmap.Config.ARGB_8888
                            );

                            bitmap.eraseColor(Color.WHITE);

                            sourcePage.render(
                                bitmap,
                                new Rect(
                                    0,
                                    0,
                                    bitmapWidth,
                                    bitmapHeight
                                ),
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                            );

                            Canvas canvas = outPage.getCanvas();

                            Paint paint = new Paint(
                                Paint.ANTI_ALIAS_FLAG |
                                Paint.FILTER_BITMAP_FLAG |
                                Paint.DITHER_FLAG
                            );

                            canvas.drawColor(Color.WHITE);
                            canvas.drawBitmap(
                                bitmap,
                                null,
                                contentRect,
                                paint
                            );

                            bitmap.recycle();
                            sourcePage.close();

                            output.finishPage(outPage);
                            outputPageIndex++;
                        }
                    }

                    if (outputPageIndex == 0) {
                        callback.onWriteFailed(
                            "No se seleccionaron páginas para imprimir."
                        );
                        return;
                    }

                    try (
                        OutputStream stream =
                            new ParcelFileDescriptor.AutoCloseOutputStream(
                                destination
                            )
                    ) {
                        output.writeTo(stream);
                        stream.flush();
                    }

                    callback.onWriteFinished(
                        new PageRange[] {
                            new PageRange(
                                0,
                                outputPageIndex - 1
                            )
                        }
                    );

                } catch (Exception e) {
                    Log.e(
                        TAG,
                        "Error generando impresión",
                        e
                    );

                    callback.onWriteFailed(
                        e.getMessage() != null
                            ? e.getMessage()
                            : "No se pudo generar el documento de impresión."
                    );

                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (Exception ignored) {}
                    }
                }
            });
        }

        @Override
        public void onFinish() {
            try {
                if (renderer != null) {
                    renderer.close();
                }
            } catch (Exception ignored) {}

            try {
                if (sourcePfd != null) {
                    sourcePfd.close();
                }
            } catch (Exception ignored) {}

            renderer = null;
            sourcePfd = null;

            printExecutor.shutdownNow();

            if (cleanup != null) {
                cleanup.run();
            }
        }
    }
}
