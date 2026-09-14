import java.util.*;
public final class CassandraETraceAudit {
 static long zipf(SplittableRandom random) {
  double theta=.99, zetaN=26.46902820178302;
  double zeta2=1+Math.pow(.5,theta), alpha=1/(1-theta);
  double spread=10000000000.0;
  double eta=(1-Math.pow(2.0/spread,1-theta))/(1-zeta2/zetaN);
  double u=random.nextDouble(),uz=u*zetaN;
  if(uz<1)return 1;
  if(uz<1+Math.pow(.5,theta))return 2;
  return Math.min(10000000000L,1+(long)(spread*Math.pow(eta*u-eta+1,alpha)));
 }
 static long fnv(long k){long h=-3750763034362895579L;for(int i=0;i<8;i++){h*=1099511628211L;h^=k&255;k>>>=8;}return h;}
 public static void main(String[]args){
  for(boolean current:new boolean[]{false,true}){
   SplittableRandom root=new SplittableRandom(20260909L);
   HashSet<Long> distinct=new HashSet<>(), pairs=new HashSet<>();
   long scans=0,inserts=0,length=0;int min=100,max=0;
   for(int w=0;w<48;w++){
    SplittableRandom random=current?root.split():new SplittableRandom(20260909L+w*0x9e3779b97f4a7c15L);
    for(int i=0;i<(1<<20);i++)random.nextInt(256);
    for(int i=0;i<20000;i++){
     long value=random.nextLong();int choice=(int)Long.remainderUnsigned(value,100);
     long key=Long.remainderUnsigned(fnv(zipf(random)),104857600);
     if(choice<95){int len=1+choice;scans++;length+=len;min=Math.min(min,len);max=Math.max(max,len);distinct.add(key);pairs.add(key*100+len);}else inserts++;
    }
   }
   System.out.printf(Locale.ROOT,"{\"generator\":\"%s\",\"scans\":%d,\"inserts\":%d,\"distinct_scan_starts\":%d,\"distinct_start_length_pairs\":%d,\"requested_rows\":%d,\"mean_scan_length\":%.9f,\"min_scan_length\":%d,\"max_scan_length\":%d}%n",current?"split-streams-v2":"legacy-gamma-v1",scans,inserts,distinct.size(),pairs.size(),length,length/(double)scans,min,max);
  }
 }
}
