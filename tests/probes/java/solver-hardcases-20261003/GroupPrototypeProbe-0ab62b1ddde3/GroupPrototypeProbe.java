package org.cgse.core;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
public class GroupPrototypeProbe {
 static int n,costLimit,ng,m;static int[][] group,a,caps;static int[] price,rhs,chosen;static int[][][] dp;static long nodes,work;static long limit=20_000_000;
 static long[][][] qdp;static int qr,ql,qn;
 static boolean dfs(int d,int left,int[] need){
  nodes++;for(int r=0;r<m;r++){work++;if(dp[d][r][left]<need[r])return false;}
  if(qdp!=null){int low=Math.max(0,need[ql]),high=Math.min(qn,-need[qr]);boolean found=false;if(low<=high)for(int w=low/64;w<=high/64;w++){work++;long mask=-1L;if(w==low/64)mask&=-1L<<(low%64);if(w==high/64)mask&=-1L>>>(63-high%64);if((qdp[d][left][w]&mask)!=0){found=true;break;}}if(!found)return false;}
  if(work>=limit)throw new IllegalStateException("limit nodes="+nodes+" work="+work);
  if(d==ng)return true;
  Integer[] options=Arrays.stream(group[d]).boxed().toArray(Integer[]::new);
  Arrays.sort(options,Comparator.comparingDouble(j->{double v=0;if(j>=0)for(int r=0;r<m;r++)v+=Math.min(need[r],a[r][j])/(double)rhs[r];return -v/(j<0?1:price[j]+1);}));
  for(int j:options){if(j>=0&&price[j]>left)continue;int[] after=need.clone();if(j>=0)for(int r=0;r<m;r++){work++;after[r]-=a[r][j];}chosen[d]=j;if(dfs(d+1,left-(j<0?0:price[j]),after))return true;}return false;
 }
 public static void main(String[] args)throws Exception{
  JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();JsonObject model=null;for(var cc:cases)if(cc.getAsJsonObject().get("id").getAsString().equals("scip/lseu-objective-1119"))model=cc.getAsJsonObject();
  n=model.getAsJsonArray("lower").size();JsonArray rows=model.getAsJsonArray("rows");List<List<Integer>> groups=new ArrayList<>();boolean[] used=new boolean[n];price=new int[n];List<int[]> coefficients=new ArrayList<>();List<Integer> goals=new ArrayList<>();
  for(int r=0;r<rows.size();r++){var row=rows.get(r).getAsJsonObject();var terms=row.getAsJsonObject("terms");int upper=row.get("upper").getAsInt();
   if(upper==1&&terms.entrySet().stream().allMatch(e->e.getValue().getAsInt()==1)){List<Integer> g=new ArrayList<>();g.add(-1);for(var e:terms.entrySet()){int id=Integer.parseInt(e.getKey());g.add(id);used[id]=true;}groups.add(g);}
   else if(r==rows.size()-1){costLimit=upper;for(var e:terms.entrySet())price[Integer.parseInt(e.getKey())]=e.getValue().getAsInt();}
   else{int[] aa=new int[n];for(var e:terms.entrySet())aa[Integer.parseInt(e.getKey())]=-e.getValue().getAsInt();coefficients.add(aa);goals.add(-upper);}
  }
  for(int j=0;j<n;j++)if(!used[j])groups.add(List.of(-1,j));a=coefficients.toArray(int[][]::new);rhs=goals.stream().mapToInt(Integer::intValue).toArray();m=a.length;ng=groups.size();chosen=new int[ng];
  int ordering=Integer.parseInt(args[1]);
  if(args.length>2){int mode=Integer.parseInt(args[2]);List<int[]> extras=new ArrayList<>();List<Integer> limits=new ArrayList<>();
   for(int r=0;r<m;r++){int gcd=0;for(int v:a[r])gcd=java.math.BigInteger.valueOf(gcd).gcd(java.math.BigInteger.valueOf(v)).intValue();if(gcd>1){for(int j=0;j<n;j++)a[r][j]/=gcd;rhs[r]=-Math.floorDiv(-rhs[r],gcd);}extras.add(a[r]);limits.add(rhs[r]);}
   if(mode==1)for(int r=0;r<m;r++)for(int q=r+1;q<m;q++){if(rhs[r]<=0||rhs[q]<=0)continue;int[] aa=new int[n];for(int j=0;j<n;j++)aa[j]=a[r][j]+a[q][j];extras.add(aa);limits.add(rhs[r]+rhs[q]);}
   a=extras.toArray(int[][]::new);rhs=limits.stream().mapToInt(Integer::intValue).toArray();m=a.length;
  }
  if(ordering==1)groups.sort(Comparator.comparingInt((List<Integer> g)->g.stream().filter(j->j>=0).mapToInt(j->price[j]).max().orElse(0)).reversed());
  if(ordering==2)groups.sort(Comparator.comparingInt((List<Integer> g)->g.size()).reversed());
  if(ordering==3)groups.sort(Comparator.comparingDouble((List<Integer> g)->{double score=0;for(int r=0;r<m;r++){int best=0;for(int j:g)if(j>=0)best=Math.max(best,a[r][j]);score+=best/(double)Math.max(1,rhs[r]);}return -score;}));
  if(ordering>=4)Collections.shuffle(groups,new Random(ordering));
  group=groups.stream().map(g->g.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);dp=new int[ng+1][m][costLimit+1];long t=System.nanoTime();
  for(int d=ng-1;d>=0;d--)for(int r=0;r<m;r++)for(int c=0;c<=costLimit;c++){int best=dp[d+1][r][c];for(int j:group[d])if(j>=0&&price[j]<=c){work++;best=Math.max(best,a[r][j]+dp[d+1][r][c-price[j]]);}dp[d][r][c]=best;}
  if(args.length>2&&Integer.parseInt(args[2])==2){qr=-1;ql=-1;for(int r=0;r<m;r++)if(rhs[r]<0&&Arrays.stream(a[r]).allMatch(x->x<=0))qr=r;for(int r=0;r<m;r++){boolean same=true;for(int j=0;j<n;j++)if(a[r][j]!=-a[qr][j])same=false;if(same)ql=r;}qn=-rhs[qr];int words=qn/64+1;qdp=new long[ng+1][costLimit+1][words];for(int c=0;c<=costLimit;c++)qdp[ng][c][0]=1;
   for(int d=ng-1;d>=0;d--)for(int c=0;c<=costLimit;c++)for(int j:group[d]){int p=j<0?0:price[j],q=j<0?0:-a[qr][j];if(p>c)continue;int skip=q/64,shift=q%64;for(int w=skip;w<words;w++){work++;qdp[d][c][w]|=qdp[d+1][c-p][w-skip]<<shift;if(shift>0&&w>skip)qdp[d][c][w]|=qdp[d+1][c-p][w-skip-1]>>>(64-shift);}}
  }
  try{System.out.println("ordering="+ordering+" found="+dfs(0,costLimit,rhs)+" nodes="+nodes+" work="+work+" time="+(System.nanoTime()-t)/1e9+" choice="+Arrays.toString(chosen));}catch(Exception e){System.out.println(e.toString());}
 }
}
