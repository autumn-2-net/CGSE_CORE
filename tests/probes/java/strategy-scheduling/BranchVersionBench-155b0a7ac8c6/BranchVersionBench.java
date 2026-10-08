import java.nio.file.*;import java.net.*;import java.lang.reflect.*;import java.util.*;
public class BranchVersionBench {
 static long median(long[] a){Arrays.sort(a);return a[a.length/2];}
 public static void main(String[]args)throws Exception{
  var versions=List.of("d26084e8","current");var methods=new ArrayList<Method>();
  for(String v:versions){var loader=new URLClassLoader(new URL[]{Path.of(".local/strategy-scheduling/"+v+"/classes").toUri().toURL()},ClassLoader.getPlatformClassLoader());methods.add(Class.forName("SchedulingCases",true,loader).getMethod("run",String.class));}
  for(String name:args){long[][] time=new long[2][101],work=new long[2][101];var status=List.of(new TreeSet<String>(),new TreeSet<String>());
   for(int i=-64;i<101;i++)for(int j=0;j<2;j++){int v=Math.floorMod(i+j,2);Object[] a=(Object[])methods.get(v).invoke(null,name);if(i>=0){time[v][i]=(long)a[0];work[v][i]=(long)a[1];status.get(v).add((String)a[2]);}}
   for(int v=0;v<2;v++)System.out.printf(Locale.ROOT,"VERSION case=%s version=%s median_ms=%.4f work=%d status=%s%n",name,versions.get(v),median(time[v])/1e6,median(work[v]),status.get(v));
  }
 }
}
