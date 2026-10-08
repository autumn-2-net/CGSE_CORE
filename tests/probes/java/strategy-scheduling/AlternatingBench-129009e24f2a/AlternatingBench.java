import java.nio.file.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;

public class AlternatingBench {
    static long median(long[] n){Arrays.sort(n);return n[n.length/2];}
    public static void main(String[] args)throws Exception{
        var methods=new ArrayList<Method>();
        for(String version:List.of("4d5891ea","bbd7a0b2","current")){
            var loader=new URLClassLoader(new URL[]{Path.of(".local/strategy-scheduling/"+version+"/classes").toUri().toURL()},ClassLoader.getPlatformClassLoader());
            methods.add(Class.forName("SchedulingCases",true,loader).getMethod("run",String.class));
        }
        for(String name:args){
            long[][] timings=new long[3][101];long[][] work=new long[3][101];var status=List.of(new TreeSet<String>(),new TreeSet<String>(),new TreeSet<String>());
            for(int i=-64;i<101;i++)for(int j=0;j<3;j++){
                int v=Math.floorMod(i+j,3);Object[] result=(Object[])methods.get(v).invoke(null,name);
                if(i>=0){timings[v][i]=(long)result[0];work[v][i]=(long)result[1];status.get(v).add((String)result[2]);}
            }
            for(int v=0;v<3;v++)System.out.printf(Locale.ROOT,"BENCH %s %s median_ms=%.4f work=%d status=%s%n",name,List.of("4d5891ea","bbd7a0b2","current").get(v),median(timings[v])/1e6,median(work[v]),status.get(v));
        }
    }
}
