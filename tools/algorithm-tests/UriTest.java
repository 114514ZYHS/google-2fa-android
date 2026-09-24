// 用 java.net.URI + URLDecoder 模拟 android.net.Uri 的解析/编码语义，验证 otpauth 解析与导出往返
import java.util.*;

public class UriTest {
    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    static final String[] ALGORITHMS = {"SHA1","SHA256","SHA512"};

    static String normalize(String v){ return v==null?"":v.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]",""); }
    static String normalizeAlgorithm(String v){
        if(v==null) return "SHA1";
        String u=v.trim().toUpperCase(Locale.US).replace("-","");
        for(String a:ALGORITHMS) if(a.equals(u)) return u;
        return "SHA1";
    }
    static int parseChoice(String v,int fb,int[] allowed){
        try{ int p=Integer.parseInt(v.trim()); if(allowed==null) return p;
            for(int c:allowed) if(c==p) return p; }catch(Exception e){}
        return fb;
    }
    // 模拟 Uri.getQueryParameter
    static String param(String uri,String key){
        int q=uri.indexOf('?'); if(q<0) return null;
        for(String seg:uri.substring(q+1).split("&")){
            int eq=seg.indexOf('='); if(eq<0) continue;
            if(seg.substring(0,eq).equals(key)){
                try{ return java.net.URLDecoder.decode(seg.substring(eq+1),"UTF-8"); }catch(Exception e){ return null; }
            }
        }
        return null;
    }
    static String enc(String s){ try{ return java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20"); }catch(Exception e){ return s; } }

    static class Acc{
        String name,issuer; String secret,algorithm; int digits,period;
        boolean pinned;
        Acc(String n,String i,String s,String a,int d,int p){name=n;issuer=i;secret=s;algorithm=a;digits=d;period=p;}
        String display(){ return (issuer==null||issuer.isEmpty())?name:issuer+" · "+name; }
        String toOtpAuth(){
            String prefix=(issuer==null||issuer.isEmpty())?"":issuer+":";
            StringBuilder b=new StringBuilder("otpauth://totp/").append(enc(prefix+name)).append("?secret=").append(secret);
            if(issuer!=null&&!issuer.isEmpty()) b.append("&issuer=").append(enc(issuer));
            if(!"SHA1".equals(algorithm)) b.append("&algorithm=").append(algorithm);
            if(digits!=6) b.append("&digits=").append(digits);
            if(period!=30) b.append("&period=").append(period);
            return b.toString();
        }
    }

    static boolean isValid(String secret){
        // 简化：长度>=16 base32 字符（10 字节）
        return !secret.isEmpty() && secret.length()>=16;
    }

    static Acc parseOtpAuth(String uri){
        try{
            if(!uri.toLowerCase(Locale.US).startsWith("otpauth://")) return null;
            String after = uri.substring("otpauth://".length());
            int slash = after.indexOf('/');
            if(slash<0) return null;
            String type = after.substring(0,slash);
            if(!"totp".equalsIgnoreCase(type)) return null;
            String path = after.substring(slash+1);
            int q = path.indexOf('?');
            String label = java.net.URLDecoder.decode(q<0?path:path.substring(0,q),"UTF-8").replaceFirst("^/","");
            String name = label;
            String labelIssuer = null;
            if(name.contains(":")){ String[] sp=name.split(":",2); labelIssuer=sp[0].trim(); name=sp[1].trim(); }
            String issuer = param(uri,"issuer");
            if(issuer==null||issuer.isEmpty()) issuer=labelIssuer;
            if(issuer==null) issuer="";
            if(name.isEmpty()) name=issuer;
            if(name.isEmpty()) name="未命名账户";
            String secret=normalize(param(uri,"secret"));
            if(secret.isEmpty()||!isValid(secret)) return null;
            String algorithm=normalizeAlgorithm(param(uri,"algorithm"));
            int digits=parseChoice(param(uri,"digits"),6,new int[]{6,8});
            int period=parseChoice(param(uri,"period"),30,null);
            if(period<5||period>300) period=30;
            return new Acc(name, issuer, secret, algorithm, digits, period);
        }catch(Exception e){ return null; }
    }

    static int pass=0,fail=0;
    static void check(String label,String expect,String actual){
        boolean ok=expect.equals(actual); if(ok)pass++;else fail++;
        System.out.printf("%s %-46s 期望=%-34s 实际=%s%n", ok?"  OK  ":" FAIL ",label,expect,actual);
    }
    
    public static void main(String[] args) throws Exception{
        System.out.println("== 标准 Google Authenticator 链接 ==");
        Acc a=parseOtpAuth("otpauth://totp/Google:alice@gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=Google");
        check("issuer","Google",a.issuer);
        check("name","alice@gmail.com",a.name);
        check("secret","JBSWY3DPEHPK3PXP",a.secret);
        check("algorithm","SHA1",a.algorithm);
        check("digits","6",String.valueOf(a.digits));
        check("period","30",String.valueOf(a.period));

        System.out.println("\n== 带 SHA256/8位/60秒 ==");
        Acc b=parseOtpAuth("otpauth://totp/GitHub:dev@x.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60");
        check("algorithm","SHA256",b.algorithm);
        check("digits","8",String.valueOf(b.digits));
        check("period","60",String.valueOf(b.period));

        System.out.println("\n== algorithm 小写/带连字符 归一化 ==");
        check("sha-256 -> SHA256","SHA256",normalizeAlgorithm("sha-256"));
        check("Sha512 -> SHA512","SHA512",normalizeAlgorithm("Sha512"));
        check("未知算法回退 SHA1","SHA1",normalizeAlgorithm("MD5"));

        System.out.println("\n== URL 编码的 issuer / 中文名称 ==");
        Acc c=parseOtpAuth("otpauth://totp/%E5%BE%AE%E8%BD%AF:user%40outlook.com?secret=JBSWY3DPEHPK3PXP&issuer=%E5%BE%AE%E8%BD%AF");
        check("中文 issuer 解码","微软",c.issuer);
        check("name 解码","user@outlook.com",c.name);

        System.out.println("\n== 只带 label、无 issuer 参数 ==");
        Acc d=parseOtpAuth("otpauth://totp/Dropbox:me@d.com?secret=JBSWY3DPEHPK3PXP");
        check("从 label 提取 issuer","Dropbox",d.issuer);
        check("提取 name","me@d.com",d.name);

        System.out.println("\n== 容错与拒绝 ==");
        check("空 secret 拒绝","null",String.valueOf(parseOtpAuth("otpauth://totp/A:b?secret=&issuer=A")));
        check("hotp 拒绝","null",String.valueOf(parseOtpAuth("otpauth://hotp/A:b?secret=JBSWY3DPEHPK3PXP&counter=1")));
        check("周期越界回退30","30",String.valueOf(parseOtpAuth("otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&period=9000").period));
        check("digits非法回退6","6",String.valueOf(parseOtpAuth("otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&digits=7").digits));
        check("无 name 用 issuer 兜底","Solo",parseOtpAuth("otpauth://totp/?secret=JBSWY3DPEHPK3PXP&issuer=Solo").name);
        check("保留字符 # 与 & 在名称中", "true",
            String.valueOf(parseOtpAuth("otpauth://totp/" + enc("A:B & C").replace("%26","%26") + "?secret=JBSWY3DPEHPK3PXP&issuer=" + enc("X & Y")).issuer.equals("X & Y")));

        System.out.println("\n== 导出→再解析 往返一致性 ==");
        Acc[] list = {
            new Acc("alice@gmail.com","Google","JBSWY3DPEHPK3PXP","SHA1",6,30),
            new Acc("dev@x.com","GitHub","JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP","SHA256",8,60),
            new Acc("微软账户","微软","JBSWY3DPEHPK3PXP","SHA512",8,30),
            new Acc("A & B","X & Y","JBSWY3DPEHPK3PXP","SHA1",6,45),
        };
        for(Acc src:list){
            Acc back=parseOtpAuth(src.toOtpAuth());
            boolean ok = back!=null && back.name.equals(src.name) && back.issuer.equals(src.issuer)
                && back.secret.equals(src.secret) && back.algorithm.equals(src.algorithm)
                && back.digits==src.digits && back.period==src.period;
            if(ok)pass++;else fail++;
            System.out.printf("%s 往返 %-24s -> %s%n", ok?"  OK  ":" FAIL ", src.display(), back==null?"解析失败":back.display()
                +" ["+back.algorithm+"/"+back.digits+"位/"+back.period+"s]");
        }

        System.out.printf("%n结果：通过 %d 项，失败 %d 项%n",pass,fail);
        if(fail>0) System.exit(1);
    }
}
