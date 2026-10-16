package de.adraxah.blockpass;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class AdminIntegrationTest {
    static final HttpClient HTTP=HttpClient.newHttpClient();static int passed;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static HttpResponse<String> call(String base,String path,String data,String cookie,String csrf,String origin)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path));if(data!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(data));if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-CSRF-Token",csrf);if(origin!=null)b.header("Origin",origin);return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args)throws Exception{
        byte[] salt=new byte[16];Arrays.fill(salt,(byte)7);
        String verifier="pbkdf2-sha256$210000$"+HexFormat.of().formatHex(salt)+"$"+HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("test-password".toCharArray(),salt,210000,256)).getEncoded());
        Path folder=Files.createTempDirectory("blockpass-admin-test-"),file=folder.resolve("data.json");
        AccessStore store=new AccessStore(file);var code=store.issue("Notch",7);var any=store.issue(null,7);
        check(!Files.readString(file).contains(code.code()),"codes encrypted on disk");
        check(new AccessStore(file).adminRows().stream().anyMatch(e->code.code().equals(e.code())),"code display survives restart");
        String receipt=AccessStore.token(24);store.claim(code.code(),"Notch",UUID.randomUUID(),receipt);store.finish(code.id(),null);store.revoke(any.id());
        var used=store.adminRows().stream().filter(e->e.id().equals(code.id())).findFirst().orElseThrow();check(used.used()&&used.status().equals("applied")&&used.code().equals(code.code())&&used.username().equals("Notch"),"used status, name and code retained after whitelist success");
        var oldData=JsonParser.parseString(Files.readString(file)).getAsJsonObject();for(var e:oldData.getAsJsonArray("entries"))e.getAsJsonObject().remove("sealedCode");Path old=Files.createTempDirectory("blockpass-legacy-test-").resolve("data.json");Files.writeString(old,oldData.toString());check(new AccessStore(old).adminRows().stream().allMatch(e->e.code()==null),"old hash-only records remain readable without invented codes");
        try(var web=new EmbeddedWebServer(new InetSocketAddress("127.0.0.1",0),store,null,"Test","","",null,new AdminAccess("admin",verifier))){web.start();String base="http://127.0.0.1:"+web.port();
            check(call(base,"/admin",null,null,null,null).statusCode()==200,"admin login page served");
            check(call(base,"/api/admin/codes",null,null,null,null).statusCode()==401,"anonymous users cannot list codes");
            check(call(base,"/api/admin/codes",null,"blockpass_admin=forged",null,null).statusCode()==401,"forged session rejected");
            check(call(base,"/api/admin/login","{\"username\":\"admin\",\"password\":\"wrong\"}",null,null,null).statusCode()==401,"wrong password rejected");
            String login="{\"username\":\"admin\",\"password\":\"test-password\"}";
            check(call(base,"/api/admin/login",login,null,null,"http://evil.test").statusCode()==403,"cross-origin login rejected");
            var auth=call(base,"/api/admin/login",login,null,null,base);check(auth.statusCode()==200,"correct login succeeds");String setCookie=auth.headers().firstValue("set-cookie").orElseThrow();String cookie=setCookie.split(";")[0],csrf=JsonParser.parseString(auth.body()).getAsJsonObject().get("csrf").getAsString();
            check(setCookie.contains("HttpOnly")&&setCookie.contains("SameSite=Strict")&&setCookie.contains("Path=/api/admin"),"session cookie protected and scoped");
            String create="{}";
            int before=store.adminRows().size();
            check(call(base,"/api/admin/codes",create,null,null,base).statusCode()==401,"anonymous code creation rejected");
            check(call(base,"/api/admin/codes",create,cookie,null,base).statusCode()==403,"code creation requires CSRF token");
            check(call(base,"/api/admin/codes",create,cookie,csrf,"http://evil.test").statusCode()==403,"cross-origin creation rejected");
            check(call(base,"/api/admin/codes","{]",cookie,csrf,base).statusCode()==400&&store.adminRows().size()==before,"malformed requests rejected without creating codes");
            var issued=call(base,"/api/admin/codes",create,cookie,csrf,base);var issuedData=JsonParser.parseString(issued.body()).getAsJsonObject();String createdCode=issuedData.get("code").getAsString();
            check(issued.statusCode()==201&&!issuedData.has("username")&&store.adminRows().size()==before+1,"admin creates a code without a player name via HTTP");
            store.check(createdCode,"Steve");store.check(createdCode,"Alex");check(true,"new unbound code accepts either valid player name before use");
            check(new AccessStore(file).adminRows().stream().anyMatch(e->createdCode.equals(e.code())&&e.boundName()==null&&!e.used()),"unbound website code persists before redemption");
            String createdReceipt=AccessStore.token(24);store.claim(createdCode,"Alex",UUID.randomUUID(),createdReceipt);
            check(store.adminRows().stream().anyMatch(e->createdCode.equals(e.code())&&e.used()&&"Alex".equals(e.username())),"player name appears in admin list only after redemption");
            boolean spent=false;try{store.claim(createdCode,"Steve",UUID.randomUUID(),AccessStore.token(24));}catch(AccessStore.Rejected e){spent=true;}check(spent,"another player cannot reuse a redeemed generic code");
            check(new AccessStore(file).adminRows().stream().anyMatch(e->createdCode.equals(e.code())&&e.used()&&"Alex".equals(e.username())),"redeemer and used status survive restart");
            var rows=call(base,"/api/admin/codes",null,cookie,null,null);check(rows.statusCode()==200&&rows.body().contains(code.code())&&rows.body().contains("Notch")&&!rows.body().contains("codeHash")&&!rows.body().contains("receiptHash")&&!rows.body().contains("sealedCode"),"authenticated list contains display fields only");
            check(call(base,"/api/admin/session",null,cookie,null,null).statusCode()==200,"login survives browser refresh");
            check(call(base,"/api/admin/codes",null,cookie,null,"http://evil.test").statusCode()==403,"cross-origin list rejected");
            check(call(base,"/codes.key",null,null,null,null).statusCode()==404&&call(base,"/data.json",null,null,null,null).statusCode()==404&&call(base,"/config.yml",null,null,null,null).statusCode()==404,"key, data and password settings not served");
            check(call(base,"/api/admin/logout","{}",cookie,null,base).statusCode()==403,"logout requires CSRF token");
            check(call(base,"/api/admin/logout","{}",cookie,csrf,base).statusCode()==200,"logout succeeds");
            check(call(base,"/api/admin/codes",null,cookie,null,null).statusCode()==401,"logged-out session cannot be reused");
            for(int i=0;i<3;i++)call(base,"/api/admin/login","{\"username\":\"admin\",\"password\":\"wrong\"}",null,null,null);
            check(call(base,"/api/admin/login",login,null,null,null).statusCode()==429,"login attempts rate limited");
        }
        byte[] key=Files.readAllBytes(folder.resolve("codes.key"));Files.delete(folder.resolve("codes.key"));boolean rejected=false;try{new AccessStore(file);}catch(java.io.IOException e){rejected=true;}check(rejected&&!Files.exists(folder.resolve("codes.key")),"missing encryption key fails without replacing it");Files.write(folder.resolve("codes.key"),key);
        check(new AccessStore(file).adminRows().stream().anyMatch(e->e.used()&&e.code().equals(code.code())),"used code display persists after restart");
        System.out.println("RESULT: "+passed+" admin checks passed.");
    }
}
