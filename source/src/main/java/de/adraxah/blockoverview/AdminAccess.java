package de.adraxah.blockoverview;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password verifier and expiring, in-memory sessions. Never sends password hashes to browsers. */
public final class AdminAccess {
    public record Session(String token,String csrf,long expires) {}
    private final String username;
    private final byte[] salt,expected;
    private final int iterations;
    private final Map<String,Session> sessions=new HashMap<>();
    public AdminAccess(String username,String encoded){
        this.username=Objects.requireNonNull(username);
        String[] parts=encoded.split("\\$");
        if(parts.length!=4||!parts[0].equals("pbkdf2-sha256"))throw new IllegalArgumentException("Admin-Passworthash ungueltig.");
        iterations=Integer.parseInt(parts[1]);salt=HexFormat.of().parseHex(parts[2]);expected=HexFormat.of().parseHex(parts[3]);
        if(iterations<100000||iterations>1000000||salt.length!=16||expected.length!=32)throw new IllegalArgumentException("Admin-Passworthash ungueltig.");
    }
    public Session login(String user,String password)throws HttpError{
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try{
            byte[] actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            boolean matches=MessageDigest.isEqual(expected,actual);Arrays.fill(actual,(byte)0);
            if(!matches||!username.equals(user))throw new HttpError(401,"Benutzername oder Passwort falsch.");
        }catch(GeneralSecurityException e){throw new IllegalStateException(e);}finally{spec.clearPassword();}
        synchronized(this){
            sessions.values().removeIf(s->s.expires()<=System.currentTimeMillis());
            if(sessions.size()>=100)throw new HttpError(429,"Zu viele Sitzungen. Bitte spaeter versuchen.");
            Session s=new Session(token(32),token(32),System.currentTimeMillis()+3600000);sessions.put(s.token(),s);return s;
        }
    }
    public synchronized Session require(String cookie)throws HttpError{
        String token="";
        if(cookie!=null)for(String part:cookie.split(";")){String[] pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals("blockoverview_admin"))token=pair[1];}
        if(token.length()>=2 && token.charAt(0)=='"' && token.charAt(token.length()-1)=='"')token=token.substring(1,token.length()-1);
        Session s=sessions.get(token);
        if(s==null||s.expires()<=System.currentTimeMillis()){sessions.remove(token);throw new HttpError(401,"Bitte anmelden.");}
        return s;
    }
    private static final SecureRandom RANDOM = new SecureRandom();
    private static String token(int length) { byte[] data = new byte[length]; RANDOM.nextBytes(data); return Base64.getUrlEncoder().withoutPadding().encodeToString(data); }
    public synchronized void logout(Session s){sessions.remove(s.token());}
}
