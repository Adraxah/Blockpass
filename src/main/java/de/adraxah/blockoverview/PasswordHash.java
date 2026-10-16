package de.adraxah.blockoverview;

import java.io.Console;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Optional local console tool; password is never a command-line argument. */
public final class PasswordHash {
    public static void main(String[] args) throws Exception {
        Console console=System.console();
        if(console==null)throw new IllegalStateException("Bitte direkt im Terminal starten.");
        char[] password=console.readPassword("Neues Admin-Passwort (mindestens 12 Zeichen): ");
        char[] confirm=console.readPassword("Wiederholen: ");
        try {
            if(password==null || confirm==null || password.length<12 || password.length>256 || !Arrays.equals(password,confirm))throw new IllegalArgumentException("Passwörter müssen übereinstimmen und 12 bis 256 Zeichen lang sein.");
            byte[] salt=new byte[16]; new SecureRandom().nextBytes(salt);
            PBEKeySpec spec=new PBEKeySpec(password,salt,210000,256);
            try { byte[] hash=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); console.printf("admin.password-hash:\n'pbkdf2-sha256$210000$%s$%s'%n",HexFormat.of().formatHex(salt),HexFormat.of().formatHex(hash)); }
            finally { spec.clearPassword(); }
        } finally { if(password!=null)Arrays.fill(password,'\0'); if(confirm!=null)Arrays.fill(confirm,'\0'); }
    }
}
