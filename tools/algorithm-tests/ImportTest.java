// 洁净版：验证 importPayload 的逐行解析、去重、统计，以及简写行的健壮性
import java.util.*;

public class ImportTest {
    static final String[] ALGORITHMS={"SHA1","SHA256","SHA512"};
    static String normalize(String v){ return v==null?"":v.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]",""); }
    static String normalizeAlgorithm(String v){ if(v==null)return "SHA1"; String u=v.trim().toUpperCase(Locale.US).replace("-","");
        for(String a:ALGORITHMS) if(a.equals(u)) return u; return "SHA1"; }
    static int parseChoice(String v,int fb,int[] al){ try{int p=Integer.parseInt(v.trim()); if(al==null)return p; for(int c:al) if(c==p)return p;}catch(Exception e){} return fb; }
    static String enc(String s){ try{return java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){return s;} }
    static String param(String uri,String key){ int q=uri.indexOf('?'); if(q<0)return null;
        for(String seg:uri.substring(q+1).split("&")){ int eq=seg.indexOf('='); if(eq<0)continue;
            if(seg.substring(0,eq).equals(key)){ try{return java.net.URLDecoder.decode(seg.substring(eq+1),"UTF-8");}catch(Exception e){return null;} } } return null; }

    static class Acc{
        String name,issuer,secret,algorithm; int digits,period;
        Acc(String n,String i,String s,String a,int d,int p){name=n;issuer=i;secret=s;algorithm=a;digits=d;period=p;}
        String display(){ return issuer==null||issuer.isEmpty()?name:issuer+" · "+name; }
        String toOtpAuth(){ String pre=(issuer==null||issuer.isEmpty())?"":issuer+":";
            StringBuilder b=new StringBuilder("otpauth://totp/").append(enc(pre+name)).append("?secret=").append(secret);
            if(issuer!=null&&!issuer.isEmpty()) b.append("&issuer=").append(enc(issuer));
            if(!"SHA1".equals(algorithm)) b.append("&algorithm=").append(algorithm);
            if(digits!=6) b.append("&digits=").append(digits);
            if(period!=30) b.append("&period=").append(period); return b.toString(); }
    }

    static boolean isValidSecret(String s){ return !s.isEmpty() && s.length()>=16; }

    static Acc parseOtpAuth(String uri){ try{
        if(!uri.toLowerCase(Locale.US).startsWith("otpauth://")) return null;
        String after=uri.substring(10); int slash=after.indexOf('/'); if(slash<0)return null;
        if(!"totp".equalsIgnoreCase(after.substring(0,slash))) return null;
        String path=after.substring(slash+1); int q=path.indexOf('?');
        String name=java.net.URLDecoder.decode(q<0?path:path.substring(0,q),"UTF-8").replaceFirst("^/","");
        String li=null; if(name.contains(":")){String[] sp=name.split(":",2); li=sp[0].trim(); name=sp[1].trim();}
        String issuer=param(uri,"issuer"); if(issuer==null||issuer.isEmpty()) issuer=li; if(issuer==null) issuer="";
        if(name.isEmpty()) name=issuer; if(name.isEmpty()) name="未命名账户";
        String secret=normalize(param(uri,"secret")); if(!isValidSecret(secret)) return null;
        int period=parseChoice(param(uri,"period"),30,null); if(period<5||period>300) period=30;
        return new Acc(name,issuer,secret,normalizeAlgorithm(param(uri,"algorithm")),
            parseChoice(param(uri,"digits"),6,new int[]{6,8}), period);
    }catch(Exception e){ return null; } }

    static boolean isPureBase32(String v){
        String t=v.trim(); if(t.isEmpty()) return false;
        return t.toUpperCase(Locale.US).matches("[A-Z2-7\\s\\-]+");
    }

    /** 从右端找第一个逗号/制表符作为分隔，并要求密钥段为纯 Base32。 */
    static Acc parseShorthand(String line){
        for(int index=line.length()-1;index>0;index--){
            char sep=line.charAt(index);
            if(sep!=','&&sep!='\t') continue;
            String name=line.substring(0,index).trim();
            String rawSecret=line.substring(index+1);
            String secret=normalize(rawSecret);
            if(name.isEmpty()||!isPureBase32(rawSecret)||!isValidSecret(secret)) continue;
            return new Acc(name,"",secret,"SHA1",6,30);
        }
        return null;
    }

    static final List<Acc> accounts=new ArrayList<>();
    static Acc findBySecret(String s){ for(Acc a:accounts) if(a.secret.equals(s)) return a; return null; }

    static int[] importPayload(String payload){
        int added=0,skipped=0,invalid=0;
        for(String rawLine:payload.split("[\\r\\n]+")){
            String line=rawLine.trim();
            if(line.isEmpty()||line.startsWith("#")) continue;
            Acc acc = line.toLowerCase(Locale.US).startsWith("otpauth://")
                    ? parseOtpAuth(line) : parseShorthand(line);
            if(acc==null){ invalid++; System.out.println("   [无效] "+line); continue; }
            if(findBySecret(acc.secret)!=null){ skipped++; continue; }
            accounts.add(acc); added++;
        }
        return new int[]{added,skipped,invalid};
    }

    static int pass=0,fail=0;
    static void eq(String label,Object exp,Object act){
        boolean ok=String.valueOf(exp).equals(String.valueOf(act)); if(ok)pass++;else fail++;
        System.out.printf("%s %-42s 期望=%-16s 实际=%s%n", ok?"  OK  ":" FAIL ",label,exp,act);
    }

    public static void main(String[] a){
        String payload = String.join("\n",
            "# 我的 2FA 备份",
            "",
            "otpauth://totp/Google:alice@gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=Google",
            "otpauth://totp/GitHub:dev@x.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60",
            "微博,JBSWY3DPEB3W64TMMQ",
            "邮箱\tKRSXG5CTMVRXEZLU",
            "   ",
            "这一行既不是链接也没有逗号",
            "otpauth://totp/Bad:x?secret=&issuer=Bad"
        );

        System.out.println("== 一次导入 ==");
        int[] r=importPayload(payload);
        eq("新增数",4,r[0]);
        eq("重复数",0,r[1]);
        eq("无效数（无逗号行 + 空密钥链接）",2,r[2]);
        eq("账户总数",4,accounts.size());
        eq("账户[0] 显示名","Google · alice@gmail.com",accounts.get(0).display());
        eq("账户[1] 算法","SHA256",accounts.get(1).algorithm);
        eq("账户[1] 位数","8",accounts.get(1).digits);
        eq("账户[2] 名称","微博",accounts.get(2).name);
        eq("账户[3] 名称","邮箱",accounts.get(3).name);

        System.out.println("\n== 重复导入同一份 ==");
        int[] r2=importPayload(payload);
        eq("新增",0,r2[0]);
        eq("重复",4,r2[1]);
        eq("无效",2,r2[2]);
        eq("总数不变",4,accounts.size());

        System.out.println("\n== 导出→导入 全量往返 ==");
        StringBuilder dump=new StringBuilder();
        for(Acc acc:accounts) dump.append(acc.toOtpAuth()).append('\n');
        List<Acc> before=new ArrayList<>(accounts);
        accounts.clear();
        int[] r3=importPayload(dump.toString());
        eq("还原数",before.size(),r3[0]);
        boolean same=true;
        for(int i=0;i<before.size();i++){
            Acc x=before.get(i), y=accounts.get(i);
            if(!x.name.equals(y.name)||!x.issuer.equals(y.issuer)||!x.secret.equals(y.secret)
               ||!x.algorithm.equals(y.algorithm)||x.digits!=y.digits||x.period!=y.period) same=false;
        }
        eq("全部字段一致",true,same);

        System.out.println("\n== 简写行健壮性 ==");
        accounts.clear();
        eq("名称无逗号","true",String.valueOf(importPayload("我的账户,JBSWY3DPEHPK3PXP")[0]==1
            && accounts.get(0).name.equals("我的账户")));
        accounts.clear();
        int[] r5=importPayload("我的,账户,JBSWY3DPEHPK3PXP");
        eq("名称含逗号仍正确",true,r5[0]==1 && accounts.get(0).name.equals("我的,账户")
            && accounts.get(0).secret.equals("JBSWY3DPEHPK3PXP"));
        accounts.clear();
        eq("密钥段含非法字符拒绝","0",String.valueOf(importPayload("名称,这不是密钥xxx")[0]));
        accounts.clear();
        eq("密钥与名称都含逗号时取最右","true",
            String.valueOf(importPayload("A,B,JBSWY3DPEHPK3PXP")[0]==1
                && accounts.get(0).name.equals("A,B")));

        System.out.printf("%n结果：通过 %d 项，失败 %d 项%n",pass,fail);
        if(fail>0) System.exit(1);
    }
}
