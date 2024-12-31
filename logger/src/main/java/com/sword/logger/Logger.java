package com.sword.logger;

import android.annotation.SuppressLint;
import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Logger {
    @SuppressLint("ConstantLocale")
    private static final SimpleDateFormat fileNameDateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    @SuppressLint("ConstantLocale")
    private static final SimpleDateFormat logContentDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final int MAX_LOG_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final String LOG_FILE_SUFFIX = ".log";
    private static final int BATCH_WRITE_COUNT = 20;
    private static String logFileDirPath = null;
    private static LinkedBlockingQueue<LogMessage> logQueue = null;
    private static final StringBuilder contentSb = new StringBuilder();
    
    private static ScheduledExecutorService logExecutor = null;
    private static final String publicTag = "Logger";
    
    public static final int LEVEL_OFF = 3;
    public static final int LEVEL_ALL = 0;
    private static final int LEVEL_VERBOSE = 1;
    private static final int LEVEL_DEBUG = 2;
    private static final int LEVEL_INFO = 3;
    private static final int LEVEL_WARN = 4;
    private static final int LEVEL_ERROR = 5;

    public static int loggingLevel = LEVEL_OFF;
    
    public static boolean logToFile = false;

    private static class LogMessage {
        String priority;
        String tag;
        String msg;

        LogMessage(String tag, String head, String msg, int level) {
            this.tag = tag;
            this.msg = head.isEmpty() ? msg : "[ " + head + " ] " + msg;
            this.priority = getPriorityString(level);
        }
    }
    
    public static void init(Application application) {
        File logFileDir = new File(application.getFilesDir(), "logger");
        if (!logFileDir.exists()) {
            if (!logFileDir.mkdir()) {
                Log.e(publicTag, "create log dir failed");
            }
        }
        logFileDirPath = logFileDir.getAbsolutePath();
        
        if (logToFile) {
            initFileLog();
        }
        application.registerActivityLifecycleCallbacks(new AppLifecycleObserver(() -> {
            d("onAppDestroy");
            release();
        }));
    }

    private static void initFileLog() {
        if (logQueue != null && logExecutor != null) {
            return;
        }
        
        if (!TextUtils.isEmpty(logFileDirPath)) {
            logExecutor = Executors.newSingleThreadScheduledExecutor();
            logQueue = new LinkedBlockingQueue<>(10000);
            startPeriodicFlush();
            d(publicTag, "logger init success, log path: " + logFileDirPath);

            clearOldLog();
        }
    }

    public static boolean isDebuggable() {
        return loggingLevel == LEVEL_ALL;
    }

    public static void openDebug() {
        loggingLevel = LEVEL_ALL;
    }

    public static void closeDebug() {
        loggingLevel = LEVEL_OFF;
    }

    public static void enableFileLog() {
        if (!logToFile) {
            logToFile = true;
            initFileLog();
        }
    }

    public static void disableFileLog() {
        if (logToFile) {
            logToFile = false;
        }
    }

    public static void v(String msg) {
        v("", msg);
    }

    public static void v(String head, String msg) {
        v("", head, msg);
    }

    public static void v(String tag, String head, String msg) {
        if (LEVEL_VERBOSE >= loggingLevel) {
            Log.v(tag.isEmpty() ? publicTag : tag, head.isEmpty() ? msg : "[ " + head + " ] " + msg);
 
            if (logToFile) {
                offerLogMessage(tag, head, msg, Log.VERBOSE);
            }
        }
    }

    public static void d(String msg) {
        d("", msg);
    }

    public static void d(String head, String msg) {
        d("", head, msg);
    }

    public static void d(String tag, String head, String msg) {
        if (LEVEL_DEBUG >= loggingLevel) {
            Log.d(tag.isEmpty() ? publicTag : tag, head.isEmpty() ? msg : "[ " + head + " ] " + msg);
            
            if (logToFile) {
                offerLogMessage(tag, head, msg, Log.DEBUG);
            }
        }
    }
    
    public static void i(String msg) {
        i("", msg);
    }

    public static void i(String head, String msg) {
        i("", head, msg);
    }

    public static void i(String tag, String head, String msg) {
        if (LEVEL_INFO >= loggingLevel) {
            Log.i(tag.isEmpty() ? publicTag : tag, head.isEmpty() ? msg : "[ " + head + " ] " + msg);
            if (logToFile) {
                offerLogMessage(tag, head, msg, Log.INFO);
            }
        }
    }

    public static void w(String msg) {
        w("", msg);
    }

    public static void w(String head, String msg) {
        w("", head, msg);
    }

    public static void w(String tag, String head, String msg) {
        if (LEVEL_WARN >= loggingLevel) {
            Log.w(tag.isEmpty() ? publicTag : tag, head.isEmpty() ? msg : "[ " + head + " ] " + msg);
            offerLogMessage(tag, head, msg, Log.WARN);
        }
    }

    public static void e(String msg) {
        e("", msg);
    }

    public static void e(String head, String msg) {
        e("", head, msg);
    }

    public static void e(String tag, String head, String msg) {
        if (LEVEL_ERROR >= loggingLevel) {
            Log.e(tag.isEmpty() ? publicTag : tag, head.isEmpty() ? msg : "[ " + head + " ] " + msg);
            offerLogMessage(tag, head, msg, Log.ERROR);
        }
    }
    
    private static String getPriorityString(int priority) {
        switch (priority) {
            case Log.VERBOSE:
                return "V";
            case Log.DEBUG:
                return "D";
            case Log.INFO:
                return "I";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            default:
                return "UNKNOWN";
        }
    }
    
    private static void clearOldLog() {
        if (logExecutor == null || TextUtils.isEmpty(logFileDirPath)) {
            return;
        }
        
        logExecutor.execute(() -> {
            //删除过去两天以前所有的日志文件
            File logFileDir = new File(logFileDirPath);
            File[] files = logFileDir.listFiles();
            if (files == null) {
                return;
            }
            
            String today = fileNameDateFormat.format(new Date());
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_MONTH, -1);
            String yesterday = fileNameDateFormat.format(calendar.getTime());
            
            for (File file : files) {
                String fileName = file.getName();
                if (fileName.endsWith(LOG_FILE_SUFFIX)) {
                    if (fileName.length() < 8) {
                        continue;
                    }
                    
                    String fileDate = fileName.substring(0, 8);

                    if (!fileDate.startsWith(today) && !fileDate.startsWith(yesterday)) {
                        if (!file.delete()) {
                            Log.w(publicTag, "Delete old log file failed: " + fileName);
                        } else {
                            Log.d(publicTag, "Delete old log file success: " + fileName);
                        }
                    }
                }
            }
        });
    }
    
    private static void startPeriodicFlush() {
        logExecutor.scheduleWithFixedDelay(Logger::flushLogToFile, 5, 5, TimeUnit.SECONDS);
    }
    
    private static void flushLogToFile() {
        if (TextUtils.isEmpty(logFileDirPath) || logQueue == null) {
            return;
        }
        
        contentSb.setLength(0);
        int count = 0;
        while (count < BATCH_WRITE_COUNT) {
            LogMessage logMessage = logQueue.poll();
            if (logMessage != null) {
                contentSb.append(logContentDateFormat.format(new Date())).append(" ").append(logMessage.tag).append("/").append(logMessage.priority).append(" ").append(logMessage.msg).append("\n");
            } else {
                break;
            }
            count++;
        }
        
        if (count > 0) {
            writeLogToFile(contentSb.toString());
        }
    }

    private static void writeLogToFile(String content) {
        if (TextUtils.isEmpty(logFileDirPath)) {
            return;
        }
        
        RandomAccessFile randomAccessFile = null;
        try {
            File logFile = new File(logFileDirPath, fileNameDateFormat.format(new Date()) + LOG_FILE_SUFFIX);
            if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                File backupLogFile = new File(logFileDirPath, logFile.getName().replace(LOG_FILE_SUFFIX, "_" + System.currentTimeMillis() + LOG_FILE_SUFFIX));
                if (!logFile.renameTo(backupLogFile)) {
                    e(publicTag, logFile.getName() + " renameTo " + backupLogFile.getName() + " failed");
                }
            }
            if (!logFile.exists()) {
                File logDir = logFile.getParentFile();
                if (logDir == null) {
                    return;
                }

                if (!logDir.exists()) {
                    if (!logDir.mkdirs()) {
                        w( logDir.getName() + " mkdirs failed");
                        return;
                    }
                }

                if (!logFile.createNewFile()) {
                    w( logFile.getName() + " create failed");
                    return;
                }
            }

            byte[] bytes = content.getBytes();
            // 打开一个随机访问文件流，按读写方式
            randomAccessFile = new RandomAccessFile(logFile, "rw");
            FileChannel channel = randomAccessFile.getChannel();
            channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    randomAccessFile.length(),
                    bytes.length
            ).put(bytes);
        } catch(Exception e) {
            e("catch " + e.getClass().getSimpleName() + " >> " + e.getMessage());
        } finally {
            if (randomAccessFile != null) {
                try {
                    randomAccessFile.close();
                } catch (Exception e) {
                    e("close log file error >> " + e.getMessage());
                }
            }
        }
    }
    
    private static void offerLogMessage(String tag, String head, String msg, int level) {
        if (TextUtils.isEmpty(logFileDirPath) || logQueue == null) {
            return;
        }
        
        if(!logQueue.offer(new LogMessage(tag, head, msg, level))) {
            Log.w(publicTag, "logQueue offer failed");
        }
    }
    
    private static void release() {
        if (!TextUtils.isEmpty(logFileDirPath) && logQueue != null) {
            flushLogToFile();
            
            if (logExecutor != null) {
                logExecutor.shutdown();
            }
        }
    }
}
