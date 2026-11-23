package com.ashrof.bluetoothcollector;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class EncryptedQueue {
    private static final String KEY_ALIAS = "bluetooth_contacts_queue_key";
    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static File write(Context context, byte[] plain) throws Exception {
        File dir = new File(context.getNoBackupFilesDir(), "telegram_outbox");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("تعذّر إنشاء مجلد الإرسال المؤقت.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        byte[] packed = new byte[1 + iv.length + encrypted.length];
        packed[0] = (byte) iv.length;
        System.arraycopy(iv, 0, packed, 1, iv.length);
        System.arraycopy(encrypted, 0, packed, 1 + iv.length, encrypted.length);
        File target = new File(dir, UUID.randomUUID().toString() + ".enc");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) { out.write(packed); out.flush(); }
        return target;
    }

    static byte[] read(Context context, String fileName) throws Exception {
        File dir = new File(context.getNoBackupFilesDir(), "telegram_outbox");
        File source = new File(dir, fileName);
        if (!source.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) {
            throw new SecurityException("Invalid queue path");
        }
        byte[] packed;\n        try (java.io.FileInputStream in = new java.io.FileInputStream(source); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {\n            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); packed = out.toByteArray();\n        }
        if (packed.length < 14) throw new IllegalStateException("ملف الإرسال المؤقت غير صالح.");
        int ivLength = packed[0] & 255;
        if (ivLength < 12 || packed.length <= 1 + ivLength) throw new IllegalStateException("بيانات التشفير غير صالحة.");
        byte[] iv = new byte[ivLength];
        byte[] ciphertext = new byte[packed.length - 1 - ivLength];
        System.arraycopy(packed, 1, iv, 0, ivLength);
        System.arraycopy(packed, 1 + ivLength, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    static void delete(Context context, String fileName) {
        File dir = new File(context.getNoBackupFilesDir(), "telegram_outbox");
        File target = new File(dir, fileName);
        try {
            if (target.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) target.delete();
        } catch (Exception ignored) { }
    }
}
