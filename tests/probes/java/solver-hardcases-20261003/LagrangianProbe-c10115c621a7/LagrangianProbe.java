package org.cgse.core;
import com.google.gson.*;import java.nio.file.*;import java.util.*;
public class LagrangianProbe{
 static int n,cap,minQuantity,limit,ng,m;static int[] price,quantity,need;static int[][] benefits,groups;static double[] lambda;static long work;
 static double[] prev,next;static int[][] choice;static int[] selected;
 static double solve(){Arrays.fill(prev,Double.POSITIVE_INFINITY);prev[0]=0;
  for(int g=0;g<ng;g++){Arrays.fill(next,Double.POSITIVE_INFINITY);Arrays.fill(choice[g],-2);for(int id:groups[g]){int q=id<0?0:quantity[id];double cost=id<0?0:price[id];if(id>=0)for(int r=0;r<m;r++)cost-=lambda[r]*benefits[r][id]/(double)need[r];
    for(int c=q;c<=cap;c++){work++;double v=prev[c-q]+cost;if(v<next[c]){next[c]=v;choice[g][c]=id;}}
   }double[] tmp=prev;prev=next;next=tmp;
  }int at=minQuantity;for(int q=minQuantity+1;q<=cap;q++)if(prev[q]<prev[at])at=q;double bound=prev[at];for(double v:lambda)bound+=v;
  for(int g=ng-1;g>=0;g--){int id=choice[g][at];selected[g]=id;if(id>=0)at-=quantity[id];}return bound;
 }
 static long exact(){long scale=1000000;long[] mult=new long[m];long rhs=0;for(int r=0;r<m;r++){mult[r]=Math.round(lambda[r]/need[r]*scale);rhs+=mult[r]*need[r];}long[] p=new long[cap+1],z=new long[cap+1];Arrays.fill(p,Long.MAX_VALUE/4);p[0]=0;
  for(int g=0;g<ng;g++){Arrays.fill(z,Long.MAX_VALUE/4);for(int id:groups[g]){int q=id<0?0:quantity[id];long cost=id<0?0:price[id]*scale;if(id>=0)for(int r=0;r<m;r++)cost-=mult[r]*benefits[r][id];for(int c=q;c<=cap;c++){work++;if(p[c-q]!=Long.MAX_VALUE/4)z[c]=Math.min(z[c],p[c-q]+cost);}}long[] t=p;p=z;z=t;}
  long best=Long.MAX_VALUE;for(int q=minQuantity;q<=cap;q++)best=Math.min(best,p[q]);return best+rhs;
 }
 public static void main(String[] args)throws Exception{var all=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();JsonObject model=null;for(var c:all)if(c.getAsJsonObject().get("id").getAsString().equals("scip/lseu-objective-"+args[1]))model=c.getAsJsonObject();n=model.getAsJsonArray("lower").size();var rows=model.getAsJsonArray("rows");List<int[]> gs=new ArrayList<>();boolean[] used=new boolean[n];price=new int[n];quantity=new int[n];List<int[]> aa=new ArrayList<>();List<Integer> bb=new ArrayList<>();int costRow=-1,quantityRow=-1;
  for(int r=0;r<rows.size();r++){var row=rows.get(r).getAsJsonObject();if(row.get("upper").getAsInt()>1&&row.getAsJsonObject("terms").entrySet().stream().allMatch(e->e.getValue().getAsInt()>0)){if(costRow<0||row.getAsJsonObject("terms").size()>rows.get(costRow).getAsJsonObject().getAsJsonObject("terms").size()){quantityRow=costRow;costRow=r;}else quantityRow=r;}}
  var qr=rows.get(quantityRow).getAsJsonObject();cap=qr.get("upper").getAsInt();int gcd=0;for(var e:qr.getAsJsonObject("terms").entrySet()){int w=e.getValue().getAsInt();quantity[Integer.parseInt(e.getKey())]=w;gcd=java.math.BigInteger.valueOf(gcd).gcd(java.math.BigInteger.valueOf(w)).intValue();}for(int i=0;i<n;i++)quantity[i]/=gcd;cap/=gcd;
  for(int r=0;r<rows.size();r++){var row=rows.get(r).getAsJsonObject();var terms=row.getAsJsonObject("terms");int upper=row.get("upper").getAsInt();if(r==quantityRow)continue;
   if(r==costRow){limit=upper;for(var e:terms.entrySet())price[Integer.parseInt(e.getKey())]=e.getValue().getAsInt();continue;}
   if(upper==1&&terms.entrySet().stream().allMatch(e->e.getValue().getAsInt()==1)){int[] g=new int[terms.size()+1];g[0]=-1;int at=1;for(var e:terms.entrySet()){int id=Integer.parseInt(e.getKey());if(used[id])throw new Error("overlap");used[id]=true;g[at++]=id;}gs.add(g);continue;}
   int[] a=new int[n];for(var e:terms.entrySet())a[Integer.parseInt(e.getKey())]=-e.getValue().getAsInt();boolean same=true;for(int i=0;i<n;i++)if(a[i]!=quantity[i]*gcd)same=false;if(same){minQuantity=(-upper+gcd-1)/gcd;continue;}aa.add(a);bb.add(-upper);
  }for(int i=0;i<n;i++)if(!used[i])gs.add(new int[]{-1,i});groups=gs.toArray(int[][]::new);ng=groups.length;benefits=aa.toArray(int[][]::new);need=bb.stream().mapToInt(Integer::intValue).toArray();m=need.length;lambda=new double[m];prev=new double[cap+1];next=prev.clone();choice=new int[ng][cap+1];selected=new int[ng];double best=Double.NEGATIVE_INFINITY,beta=Double.parseDouble(args.length>2?args[2]:"1.5");int stalled=0;
  for(int iter=0;iter<3000&&work<20_000_000;iter++){double value=solve();double norm=0;double[] grad=new double[m];boolean feasible=true;int cost=0;for(int id:selected)if(id>=0)cost+=price[id];for(int r=0;r<m;r++){int total=0;for(int id:selected)if(id>=0)total+=benefits[r][id];if(total<need[r])feasible=false;grad[r]=1-total/(double)need[r];norm+=grad[r]*grad[r];}
   if(value>best+1e-5){best=value;stalled=0;if(iter%20==0||value>limit)System.out.println("iter="+iter+" lb="+value+" cost="+cost+" work="+work);if(value>limit+1e-7){long proof=exact();System.out.println("exact="+proof+" / 1000000 work="+work);if(proof>limit*1000000L){System.out.println("UNSAT");return;}}}else stalled++;
   if(feasible&&cost<=limit){System.out.println("SAT cost="+cost+" iter="+iter+" work="+work+" chosen="+Arrays.toString(selected));return;}if(stalled>=40){beta*=0.7;stalled=0;}double step=beta*(limit+1-value)/norm;for(int r=0;r<m;r++)lambda[r]=Math.max(0,lambda[r]+step*grad[r]);
  }System.out.println("UNKNOWN best="+best+" work="+work+" cap="+cap+" groups="+ng+" lambda="+Arrays.toString(lambda));
 }
}
