import java.nio.file.*; import java.util.*;
import org.cgse.core.*;
public class DumpOrderSweep {public static void main(String[] a) throws Exception {
 DumpReplay.main(new String[]{a[0], a[1]+".empty", "1", "__none__"});
 DumpReplay.output=new java.io.PrintWriter(Files.newBufferedWriter(Path.of(a[1])));
 var stock=new LinkedHashMap<>(DumpReplay.stock);stock.put("k1979",24895284983L);
 var catalog=DumpReplay.compiler("k1240").catalog();
 for(int i=0;i<60;i++){
  var order=new ArrayList<>(catalog);Collections.shuffle(order,new Random(26092600L+i));
  DumpReplay.run(new GraphCompiler<>(order),"k1240",Integer.MAX_VALUE,"shuffle"+i,stock,"k1979=24895284983");
 }
 DumpReplay.output.close();System.out.println(DumpReplay.totals);}}
