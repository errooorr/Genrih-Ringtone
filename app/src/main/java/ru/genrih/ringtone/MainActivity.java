package ru.genrih.ringtone;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ContentResolver;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.widget.*;
import android.view.View;
import java.io.*;
import java.nio.ByteBuffer;

public class MainActivity extends Activity {
    private static final int PICK = 41;
    private Uri selected;
    private TextView status;
    private Button install;
    private boolean isMp4;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 55, 36, 36);
        TextView title = new TextView(this); title.setText("Генрих Рингтон 🎵"); title.setTextSize(26); root.addView(title);
        TextView hint = new TextView(this); hint.setText("Выбери MP3 или MP4 с аудио AAC. MP4 преобразуется в M4A без перекодирования."); root.addView(hint);
        Button choose = new Button(this); choose.setText("Выбрать MP3 / MP4"); root.addView(choose);
        install = new Button(this); install.setText("Установить на звонок"); install.setEnabled(false); root.addView(install);
        status = new TextView(this); status.setText("Файл не выбран"); root.addView(status);
        setContentView(root);
        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp3", "video/mp4"});
            startActivityForResult(i, PICK);
        });
        install.setOnClickListener(v -> {
            if (!Settings.System.canWrite(this)) {
                status.setText("Разреши изменение системных настроек, затем вернись и нажми ещё раз.");
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(i); return;
            }
            install.setEnabled(false); status.setText("Подготавливаю мелодию…");
            new Thread(() -> {
                try { Uri uri = createRingtone(); runOnUiThread(() -> {
                    boolean ok = false;
                    try { RingtoneManager.setActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE, uri);
                        ok = uri.equals(RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE));
                    } catch (Exception e) { status.setText("Ошибка установки: " + e.getMessage()); install.setEnabled(true); return; }
                    status.setText(ok ? "Готово! Мелодия звонка установлена." : "Файл добавлен, но система не подтвердила установку. Проверь настройки звука.");
                    install.setEnabled(true);
                }); } catch(Exception e) { runOnUiThread(() -> { status.setText("Ошибка: " + e.getMessage()); install.setEnabled(true); }); }
            }).start();
        });
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req != PICK || result != RESULT_OK || data == null) return;
        selected = data.getData(); if(selected == null) return;
        String mime = getContentResolver().getType(selected);
        String name = selected.getLastPathSegment();
        isMp4 = "video/mp4".equals(mime) || (name != null && name.toLowerCase().endsWith(".mp4"));
        if (!isMp4 && !("audio/mpeg".equals(mime) || "audio/mp3".equals(mime) || (name != null && name.toLowerCase().endsWith(".mp3")))) {
            status.setText("Поддерживаются только MP3 и MP4."); install.setEnabled(false); return;
        }
        status.setText("Выбрано: " + (name == null ? selected.toString() : name)); install.setEnabled(true);
    }
    private Uri createRingtone() throws Exception {
        ContentResolver cr = getContentResolver();
        String mime = isMp4 ? "audio/mp4" : "audio/mpeg";
        String ext = isMp4 ? ".m4a" : ".mp3";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, "Genrih_Ringtone_" + System.currentTimeMillis() + ext);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, mime);
        cv.put(MediaStore.Audio.Media.IS_RINGTONE, 1);
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/Genrih/");
        cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri dst = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
        if (dst == null) throw new IOException("Не удалось создать запись в MediaStore");
        try {
            if(isMp4) {
                File tmp = new File(getCacheDir(), "ringtone_" + System.nanoTime() + ".m4a");
                try {
                    extractAac(selected,tmp);
                    try(InputStream in = new FileInputStream(tmp); OutputStream out = cr.openOutputStream(dst)) { copy(in,out); }
                } finally { tmp.delete(); }
            } else {
                try(InputStream in = cr.openInputStream(selected); OutputStream out = cr.openOutputStream(dst)) { copy(in,out); }
            }
            ContentValues ready = new ContentValues(); ready.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(dst, ready, null, null);
            return dst;
        } catch(Exception ex) { cr.delete(dst,null,null); throw ex; }
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        if(in == null || out == null) throw new IOException("Не удалось открыть файл");
        byte[] buf = new byte[65536]; int n; while((n=in.read(buf)) != -1) out.write(buf,0,n);
    }
    private void extractAac(Uri src,File output) throws Exception {
        MediaExtractor ex = new MediaExtractor(); MediaMuxer mux = null;
        try {
            ex.setDataSource(this, src, null);
            int audio = -1; MediaFormat fmt = null;
            for(int i=0;i<ex.getTrackCount();i++) {
                MediaFormat f=ex.getTrackFormat(i);
                String type=f.getString(MediaFormat.KEY_MIME);
                if("audio/mp4a-latm".equals(type)) { audio=i; fmt=f; break; }
            }
            if(audio<0) throw new IOException("В MP4 нет дорожки AAC. Другие аудиокодеки пока не поддерживаются.");
            ex.selectTrack(audio);
            mux = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track=mux.addTrack(fmt); mux.start();
            int max=fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1048576;
            ByteBuffer buffer=ByteBuffer.allocateDirect(Math.max(max,1048576));
            android.media.MediaCodec.BufferInfo info=new android.media.MediaCodec.BufferInfo();
            while(true) {
                buffer.clear(); int n=ex.readSampleData(buffer,0); if(n<0) break;
                info.offset=0; info.size=n; info.presentationTimeUs=ex.getSampleTime(); info.flags=ex.getSampleFlags();
                mux.writeSampleData(track,buffer,info); ex.advance();
            }
        } finally { if(mux!=null) { try { mux.stop(); } catch(Exception ignored) {} mux.release(); } ex.release(); }
    }
}
