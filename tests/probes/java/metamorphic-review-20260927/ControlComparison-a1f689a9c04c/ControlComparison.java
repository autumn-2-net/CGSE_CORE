import java.net.*;
import java.nio.file.*;
public final class ControlComparison {
    public static void main(String[] args)throws Exception {
        try(var old=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader());
            var next=new URLClassLoader(new URL[]{Path.of(args[1]).toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class<?>[] types={old.loadClass("BenchmarkBridge"),next.loadClass("BenchmarkBridge")};
            for(var type:types)type.getMethod("load",String.class).invoke(null,args[2]);
            for(int index:new int[]{0,1,2,10}){
                for(int i=0;i<64;i++)for(var type:types)type.getMethod("run",int.class).invoke(null,index);
                for(int trial=0;trial<101;trial++)for(int p=0;p<2;p++){
                    int version=(trial+p)&1;
                    Object[] r=(Object[])types[version].getMethod("run",int.class).invoke(null,index);
                    System.out.println((version==0?"old":"new")+"\t"+trial+"\t"+r[0]+"\t"+r[1]+"\t"+r[2]+"\t"+r[3]);
                }
            }
        }
    }
}
