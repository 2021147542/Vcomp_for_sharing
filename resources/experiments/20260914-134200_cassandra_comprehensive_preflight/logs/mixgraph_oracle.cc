#include <random>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <vector>
int main(){
for(uint64_t seed: {0ULL,1ULL,5489ULL,20260909ULL,18446744073709551615ULL}) {std::mt19937_64 r(seed); printf("mt %llu",(unsigned long long)seed); for(int i=0;i<4;i++) printf(" %llu",(unsigned long long)r());printf("\n");}
std::vector<int64_t> w;int64_t amp=0,sum=0;for(int p=30;p>=1;p--){double v=14.18*std::exp(-2.917*p)+.0164*std::exp(-.08082*p);if(!amp)amp=std::floor(1/v)+1;w.push_back(std::floor(amp*v));sum+=w.back();} std::mt19937_64 r(sum);for(int i=0;i<30;i++)std::swap(w[i],w[r()%30]);printf("weights");for(auto v:w)printf(" %ld",v);printf("\n");
for(int64_t space:{104857600LL,1000LL})for(int64_t input:{0LL,1LL,829LL,830LL,969LL,970LL,999LL,100000LL,104857599LL}){int64_t rangeSize=space/30;int64_t x=input%sum,i=0;while(i<29&&x>=w[i])x-=w[i++];double u=double(input%rangeSize)/rangeSize;int64_t seed=ceil(pow(u/.002312,1/.3467));std::mt19937_64 kr(seed);printf("key %ld %ld %llu\n",space,input,(unsigned long long)(i*rangeSize+kr()%rangeSize));}
}
