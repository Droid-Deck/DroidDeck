#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define N (32u*1024u*1024u)
static int zero_fd;
static void *thread_test(void *arg)
{
    unsigned seed = (unsigned)(uintptr_t)arg;
    uint32_t *p = mmap(NULL,N,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
    assert(p != MAP_FAILED && (uintptr_t)p+N <= 0x100000000ull);
    for (size_t i=0;i<N/4;i++) p[i]=(uint32_t)i^seed;
    for (size_t i=0;i<N/4;i++) assert(p[i]==((uint32_t)i^seed));
    assert(!munmap(p,N));
    return NULL;
}
int main(void)
{
    zero_fd=open("/dev/zero",O_RDWR|O_CLOEXEC); assert(zero_fd>=0);
    struct stat st; assert(!fstat(zero_fd,&st)); char value[48];
    snprintf(value,sizeof(value),"%ju",(uintmax_t)st.st_rdev);
    assert(!setenv("DROIDDECK_LOW32_RDEV",value,1));
    assert(!setenv("DROIDDECK_LOW32_MODE","low32",1));
    /* Existing low-address memory must survive device mappings. */
    uint32_t *guard=mmap((void *)0x10000000,128u*1024u*1024u,PROT_READ|PROT_WRITE,
                         MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
    assert(guard==(void *)0x10000000);guard[0]=0xdeadbeef;guard[32767]=0xcafef00d;
    pthread_t threads[8];
    for (uintptr_t i=0;i<8;i++) assert(!pthread_create(&threads[i],NULL,thread_test,(void *)i));
    for (unsigned i=0;i<8;i++) assert(!pthread_join(threads[i],NULL));
    assert(guard[0]==0xdeadbeef && guard[32767]==0xcafef00d);
    puts("PASS concurrent 32MiB mappings, full pattern readback and occupied-memory preservation");
    assert(!munmap(guard,128u*1024u*1024u));
    /* Caller-specified address and MAP_FIXED semantics are preserved. */
    void *explicit=mmap((void *)0x30000000,4096,PROT_READ|PROT_WRITE,
                         MAP_SHARED|MAP_FIXED_NOREPLACE,zero_fd,0);
    assert(explicit==(void *)0x30000000); assert(!munmap(explicit,4096));
    int mem=memfd_create("low32-nontarget",0);assert(mem>=0);assert(!ftruncate(mem,N));
    uint32_t *q=mmap((void *)0x500000000ull,N,PROT_READ|PROT_WRITE,MAP_SHARED,mem,0);
    assert(q==(void *)0x500000000ull);q[0]=123;assert(q[0]==123);assert(!munmap(q,N));close(mem);
    puts("PASS unrelated regular-file mapping and explicit-address forwarding");
    errno=0;
    void *bad=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,1);
    assert(bad==MAP_FAILED && errno==EINVAL);
    puts("PASS invalid device offset retains EINVAL");
    /* Real game initialization creates many small control/buffer mappings. */
    void *small[2000];
    for(unsigned i=0;i<2000;i++) {
      small[i]=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
      assert(small[i]!=MAP_FAILED && (uintptr_t)small[i]+4096<=0x100000000ull);
      *(uint32_t *)small[i]=i;
    }
    for(unsigned i=0;i<2000;i++){
      assert(*(uint32_t *)small[i]==i);assert(!munmap(small[i],4096));
    }
    puts("PASS 2000 simultaneous small mappings without address-slot exhaustion");
    /* A fragmented Wine address space has usable holes away from every
     * 4MiB probe location. The mapping walk must find them without clobbering. */
    const uintptr_t lo=0x10000000u, hi=0xffff0000u;
    void *reservation=mmap((void *)lo,hi-lo,PROT_NONE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_NORESERVE|MAP_FIXED_NOREPLACE,-1,0);
    assert(reservation==(void *)lo);
    void *holes[64], *fragmented[64];
    for (unsigned i=0;i<64;i++) {
      holes[i]=(void *)(lo+0x12345000u+(uintptr_t)i*0x10000u);
      assert(!munmap(holes[i],4096));
    }
    for (unsigned i=0;i<64;i++) {
      fragmented[i]=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
      assert(fragmented[i]!=MAP_FAILED);
      int found=0;for(unsigned j=0;j<64;j++)found|=fragmented[i]==holes[j];assert(found);
      *(uint32_t *)fragmented[i]=i+77;
    }
    for (unsigned i=0;i<64;i++) assert(*(uint32_t *)fragmented[i]==i+77);
    for (unsigned i=0;i<64;i++) assert(!munmap(fragmented[i],4096));
    assert(!munmap(reservation,hi-lo));
    puts("PASS fragmented low32 mappings find 64 page holes beyond coarse probes");
    /* Small allocations must consume existing page holes rather than punch
     * coarse-grid holes into the only large interval needed by a later map. */
    reservation=mmap((void *)lo,hi-lo,PROT_NONE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_NORESERVE|MAP_FIXED_NOREPLACE,-1,0);
    assert(reservation==(void *)lo);
    for (unsigned i=0;i<64;i++) assert(!munmap(holes[i],4096));
    const uintptr_t large_address=0x70000000u;
    const size_t large_size=16u*1024u*1024u;
    assert(!munmap((void *)large_address,large_size));
    for (unsigned i=0;i<64;i++) {
      fragmented[i]=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
      assert(fragmented[i]!=MAP_FAILED);
      int found=0;for(unsigned j=0;j<64;j++)found|=fragmented[i]==holes[j];assert(found);
      *(uint32_t *)fragmented[i]=i+99;
    }
    uint32_t *large=mmap(NULL,large_size,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
    assert(large==(void *)large_address);
    for(size_t i=0;i<large_size/4;i++)large[i]=(uint32_t)i^0xa5a5a5a5u;
    for(size_t i=0;i<large_size/4;i++)assert(large[i]==((uint32_t)i^0xa5a5a5a5u));
    for(unsigned i=0;i<64;i++){
      assert(*(uint32_t *)fragmented[i]==i+99);assert(!munmap(fragmented[i],4096));
    }
    assert(!munmap(large,large_size));assert(!munmap(reservation,hi-lo));
    puts("PASS page-sized mapping density preserves subsequent contiguous 16MiB allocation");
    assert(!setenv("DROIDDECK_LOW32_RDEV","999999999",1));
    q=mmap((void *)0x510000000ull,4096,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
    assert(q==(void *)0x510000000ull);assert(!munmap(q,4096));
    puts("PASS mismatched device remains untouched");
    assert(!unsetenv("DROIDDECK_LOW32_MODE"));
    q=mmap((void *)0x520000000ull,4096,PROT_READ|PROT_WRITE,MAP_SHARED,zero_fd,0);
    assert(q==(void *)0x520000000ull);assert(!munmap(q,4096));
    puts("PASS opt-in disabled by default");
    close(zero_fd);return 0;
}
