package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;

import net.kdt.pojavlaunch.*;
import org.apache.commons.io.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    private static final int TIME_OUT = 30000;
    /** 方块鱼：大文件分段并发下载（4 连接），小文件仍走单线程 */
    private static final int DOWNLOAD_THREADS = 4;
    private static final long MULTI_THREAD_MIN_SIZE = 3L * 1024 * 1024;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            // System.out.println("Connecting: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        } catch (IOException e) {
            if (out.length() < 1) { // Only delete it if file is 0 bytes cause this file might already be downloaded and something else went wrong.
                Log.i("DownloadUtils", "Cleaning up failed download: " + out.getAbsolutePath());
                out.delete();
                throw e;
            }
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);
        if (outputFile.exists() && !outputFile.delete()) {
            Log.i("DownloadUtils", "无法删除旧的下载文件：" + outputFile.getAbsolutePath());
        }

        // 方块鱼：大文件先尝试 4 线程分段下载（服务器支持 Range 时生效），失败就回退单线程
        try {
            if (downloadMultiThreaded(urlInput, outputFile, monitor)) return;
        } catch (Throwable error) {
            Log.i("DownloadUtils", "多线程下载失败，回退单线程", error);
            if (outputFile.exists()) outputFile.delete();
        }
        downloadSingleThreaded(urlInput, outputFile, buffer, monitor, 3);
    }

    /** 单线程下载（带重试） */
    private static void downloadSingleThreaded(String urlInput, File outputFile, @Nullable byte[] buffer,
                                               Tools.DownloaderFeedback monitor, int attempts) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt += 1) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
                conn.setConnectTimeout(TIME_OUT);
                conn.setReadTimeout(TIME_OUT);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                InputStream readStr = conn.getInputStream();
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    int current;
                    int overall = 0;
                    int length = conn.getContentLength();

                    if (buffer == null) buffer = new byte[65535];

                    while ((current = readStr.read(buffer)) != -1) {
                        overall += current;
                        fos.write(buffer, 0, current);
                        monitor.updateProgress(overall, length);
                    }
                    conn.disconnect();
                }
                return;
            } catch (IOException error) {
                lastError = new IOException("Unable to download from " + urlInput + "（第 " + attempt + " 次尝试失败）", error);
                Log.i("DownloadUtils", "下载失败，重试中：" + urlInput, error);
                if (outputFile.exists()) outputFile.delete();
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw lastError == null ? new IOException("下载失败：" + urlInput) : lastError;
    }

    /** 4 线程 Range 分段下载；服务器不支持 Range 或文件太小返回 false（交给单线程） */
    private static boolean downloadMultiThreaded(String urlInput, File outputFile,
                                                 Tools.DownloaderFeedback monitor) throws Exception {
        long total = probeContentLength(urlInput);
        if (total < MULTI_THREAD_MIN_SIZE) return false;

        int threads = DOWNLOAD_THREADS;
        long chunk = total / threads;
        final java.util.concurrent.atomic.AtomicLong downloaded =
                new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
        raf.setLength(total);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i += 1) {
            final long start = i * chunk;
            final long end = (i == threads - 1) ? (total - 1) : (start + chunk - 1);
            futures.add(pool.submit(() -> {
                if (failed.get()) return;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
                    conn.setConnectTimeout(TIME_OUT);
                    conn.setReadTimeout(TIME_OUT);
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    if (conn.getResponseCode() != 206) {
                        failed.set(true);
                        return;
                    }
                    try (InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[64 * 1024];
                        long position = start;
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            synchronized (raf) {
                                raf.seek(position);
                                raf.write(buf, 0, read);
                            }
                            position += read;
                            downloaded.addAndGet(read);
                            monitor.updateProgress((int) Math.min(Integer.MAX_VALUE, downloaded.get()),
                                    (int) Math.min(Integer.MAX_VALUE, total));
                        }
                    }
                    conn.disconnect();
                } catch (Throwable error) {
                    failed.set(true);
                }
            }));
        }

        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get();
            } catch (Throwable ignored) {
            }
        }
        pool.shutdown();
        raf.close();

        if (failed.get() || outputFile.length() != total) {
            if (outputFile.exists()) outputFile.delete();
            return false;
        }
        Log.i("DownloadUtils", "多线程下载完成：" + urlInput + "（" + total + " 字节）");
        return true;
    }

    /** 探测文件总大小，顺便确认服务器是否支持 Range（不支持返回 -1） */
    private static long probeContentLength(String urlInput) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlInput).openConnection();
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Range", "bytes=0-0");
            if (conn.getResponseCode() != 206) return -1;
            String contentRange = conn.getHeaderField("Content-Range");
            if (contentRange == null) return -1;
            int slash = contentRange.lastIndexOf('/');
            if (slash < 0) return -1;
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Throwable error) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

