#define _GNU_SOURCE
#include <unistd.h>
#include <errno.h>
static int injected_read_fd=-1;
static unsigned int injected_reads;
static ssize_t fixture_read(int fd,void *buffer,size_t count){
 if(fd==injected_read_fd){injected_reads++;if(injected_reads==1){errno=EINTR;return -1;}if(count>1)count=1;}
 return read(fd,buffer,count);
}
#define read fixture_read
#define MSF_BROKER_TEST_INCLUDE
#include "msf-scanner-broker.c"
#undef read
#include <assert.h>
/* Real ordinary pipe/child fixture for the trusted handshake only. No namespaces, credentials,
 * cgroups, scanner, PDEATHSIG delivery or privileged containment are exercised here. */
static void child_gate_fixture(unsigned int mode){int go[2],armed[2];
 assert(pipe2(go,O_CLOEXEC)==0&&pipe2(armed,O_CLOEXEC)==0);
 pid_t child=fork();assert(child>=0);
 if(child==0){close(go[1]);close(armed[0]);int result=armed_wait_for_go(go[0],armed[1],monotonic_ms()+250);_exit(result==0?0:126);}
 close(go[0]);close(armed[1]);unsigned char marker=0;
 assert(bounded_io(armed[0],&marker,1,false,monotonic_ms()+2000)==0&&marker==CHILD_ARMED_V2);close(armed[0]);
 int status;assert(waitpid(child,&status,WNOHANG)==0); /* ARMED is not permission to exec. */
 if(mode<2){marker=mode==0?CHILD_GO_V2:1;assert(write(go[1],&marker,1)==1);}
 if(mode!=3){close(go[1]);go[1]=-1;} /* mode3 retains the writer: only child's deadline can fail it. */
 uint64_t until=monotonic_ms()+2000;pid_t waited=0;
 while(monotonic_ms()<until){waited=waitpid(child,&status,WNOHANG);if(waited!=0)break;struct timespec delay={.tv_sec=0,.tv_nsec=1000000};(void)nanosleep(&delay,NULL);}
 assert(waited==child&&WIFEXITED(status)&&WEXITSTATUS(status)==(mode==0?0:126));if(go[1]>=0)close(go[1]);
}
static void ordinary_protection_fixture(void){pid_t child=fork();assert(child>=0);
 if(child==0){int installed=0;
  if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)!=0||arm_parent_death()!=0||prctl(PR_GET_PDEATHSIG,&installed,0,0,0)!=0||installed!=SIGKILL||scanner_seccomp()!=0)_exit(126);
  errno=0;if(prctl(PR_SET_PDEATHSIG,0,0,0,0)!=-1||errno!=EPERM)_exit(126);
  errno=0;if(syscall(SYS_setresuid,getuid(),geteuid(),geteuid())!=-1||errno!=EPERM)_exit(126);
  errno=0;if(syscall(SYS_setresgid,getgid(),getegid(),getegid())!=-1||errno!=EPERM)_exit(126);
  const char marker[]="unpriv-protection-filter: PASS\n";if(write(1,marker,sizeof(marker)-1)!=(ssize_t)(sizeof(marker)-1))_exit(126);_exit(0);
 }
 int status=0;pid_t waited=0;uint64_t until=monotonic_ms()+3000;
 while(monotonic_ms()<until){waited=waitpid(child,&status,WNOHANG);if(waited!=0)break;struct timespec delay={.tv_sec=0,.tv_nsec=1000000};(void)nanosleep(&delay,NULL);}
 if(waited==0){(void)kill(child,SIGKILL);(void)waitpid(child,&status,0);assert(!"fixture deadline exceeded");}
 assert(waited==child&&WIFEXITED(status)&&WEXITSTATUS(status)==0);
}
static void setup_timer_fixture(unsigned int mode){pid_t child=fork();assert(child>=0);
 if(child==0){uint64_t deadline=monotonic_ms()+100;
  if(setup_timer(deadline)!=0)_exit(125);
  if(mode==0)_exit(finish_setup_timer(deadline)==0?0:125);
  if(mode==1){sigset_t alarms;if(sigemptyset(&alarms)!=0||sigaddset(&alarms,SIGALRM)!=0||sigprocmask(SIG_BLOCK,&alarms,NULL)!=0||raise(SIGALRM)!=0)_exit(125);_exit(finish_setup_timer(deadline)!=0?0:125);}
  if(mode==2)_exit(finish_setup_timer(monotonic_ms())!=0?0:125);
  for(;;)pause(); /* mode3 proves the trusted handler exits even when no parent progress occurs. */
 }
 int status=0;pid_t waited=0;uint64_t until=monotonic_ms()+3000;
 while(monotonic_ms()<until){waited=waitpid(child,&status,WNOHANG);if(waited!=0)break;struct timespec delay={.tv_sec=0,.tv_nsec=1000000};(void)nanosleep(&delay,NULL);}
 if(waited==0){(void)kill(child,SIGKILL);(void)waitpid(child,&status,0);assert(!"fixture deadline exceeded");}
 assert(waited==child&&WIFEXITED(status)&&WEXITSTATUS(status)==(mode==3?126:0));
}
/* Full candidate parsing only: these fixtures never establish privileged resource acceptance. */
static int memory_profile_fixture(uint64_t memory,struct profile *parsed){
 const char *realm="00000000-0000-0000-0000-000000000001";
 const char *paths[]={"scanner/clamscan","definitions/main.cvd","definitions/daily.cvd","definitions/bytecode.cvd","usr/lib/libclamav.so.12","scanner/clamav-package.manifest"};
 const char *hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
 unsigned char data[FRAME_MAX];
 int n=snprintf((char *)data,sizeof(data),"format=MSF_SCANNER_BROKER_PROFILE_V1\nrealm-id=%s\nslot-count=1\ncaller-uid=1000\ncaller-gid=1000\nscanner-uid=2000\nscanner-gid=2000\nmemory-bytes=%llu\nscratch-bytes=134217728\nmax-bytes=1024\ncpu-seconds=3\nmax-pids=16\nmax-fds=64\nscanner-sha256=%s\nslot.0.id=scan-0\nresource-count=6\n",realm,(unsigned long long)memory,hash);
 assert(n>0&&(size_t)n<sizeof(data));size_t length=(size_t)n;
 for(size_t i=0;i<ARRAY_LEN(paths);i++){
  n=snprintf((char *)data+length,sizeof(data)-length,"resource.%zu.path=%s\nresource.%zu.sha256=%s\n",i,paths[i],i,hash);
  assert(n>0&&(size_t)n<sizeof(data)-length);length+=(size_t)n;
 }
 unsigned char digest[32];hash_bytes(data,length,digest);char id[65];
 for(size_t i=0;i<32;i++){n=snprintf(id+i*2,3,"%02x",digest[i]);assert(n==2);}id[64]=0;
 memset(parsed,0,sizeof(*parsed));return parse_profile(data,length,id,realm,"scan-0",parsed);
}
/* SHA padding/block/read boundaries plus multiple stream segmentations. */
static void digest_vectors_fixture(void){
 static const struct {size_t length;const char *digest;} vectors[]={
 {0,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
 {1,"6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"},
 {55,"463eb28e72f82e0a96c0a4cc53690c571281131f672aa229e0d45ae59b598b59"},
 {56,"da2ae4d6b36748f2a318f23e7ab1dfdf45acdc9d049bd80e59de82a60895f562"},
 {63,"29af2686fd53374a36b0846694cc342177e428d1647515f078784d69cdb9e488"},
 {64,"fdeab9acf3710362bd2658cdc9a29e8f9c757fcf9811603a8c447cd1d9151108"},
 {65,"4bfd2c8b6f1eec7a2afeb48b934ee4b2694182027e6d0fc075074f2fabb31781"},
 {127,"92ca0fa6651ee2f97b884b7246a562fa71250fedefe5ebf270d31c546bfea976"},
 {128,"471fb943aa23c511f6f72f8d1652d9c880cfa392ad80503120547703e56a2be5"},
 {129,"5099c6a56203f9687f7d33f4bfdf576d31dc91f6b695ecea38b2770c87631135"},
 {32767,"12ac503ad686bf1dbc1c7b3e0d83c02a8eae02da9519d0ea51b6aa4198d08ddf"},
 {32768,"09fed9cbfb98b6ab0f3e8ff63b7b1f9b0e07d58b225295c78fdc023cc4985a72"},
 {32769,"346e2310c7233f7723048e8289ba7a299d635ded9723dfea5248773f5b7684b8"},
 {65536,"4b640d85ab3ba30fd02c9fc9db4a8928f416322ad27022ea58a65aaee68a4df2"},
 };
 static const size_t chunks[]={1,7,63,64,65,4093,32768};
 unsigned char data[65536],digest[32],expected[32];
 for(size_t i=0;i<sizeof(data);i++)data[i]=(unsigned char)(i%251);
 for(size_t v=0;v<ARRAY_LEN(vectors);v++){
  assert(hex_decode(vectors[v].digest,32,expected));
  hash_bytes(data,vectors[v].length,digest);assert(equal(digest,expected,32));
  for(size_t c=0;c<ARRAY_LEN(chunks);c++){
   struct sha256 hash;sha_init(&hash);sha_update(&hash,data,0);
   for(size_t offset=0;offset<vectors[v].length;){size_t n=vectors[v].length-offset;if(n>chunks[c])n=chunks[c];sha_update(&hash,data+offset,n);offset+=n;}
   sha_final(&hash,digest);assert(equal(digest,expected,32));
  }
  printf("hash-vector-%zu=%s\n",vectors[v].length,vectors[v].digest);
 }
 int descriptors[2];assert(pipe2(descriptors,O_CLOEXEC)==0);
 assert(write(descriptors[1],"abc",3)==3);assert(close(descriptors[1])==0);
 injected_read_fd=descriptors[0];injected_reads=0;assert(hash_fd(descriptors[0],digest)==0&&injected_reads==5);
 injected_read_fd=-1;assert(close(descriptors[0])==0);
 assert(hex_decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",32,expected)&&equal(digest,expected,32));
 assert(pipe2(descriptors,O_CLOEXEC|O_NONBLOCK)==0);memset(digest,0xa5,sizeof(digest));
 assert(hash_fd(descriptors[0],digest)==-1&&errno==EAGAIN);
 for(size_t i=0;i<sizeof(digest);i++)assert(digest[i]==0xa5);
 assert(close(descriptors[0])==0&&close(descriptors[1])==0);
 unsigned char facts[32],again[32];
 if(crypto_library_digest(facts)==0){provider_facts(facts);provider_facts(again);assert(equal(facts,again,32));}
 else { /* A writable host library must fail closed rather than acquire provider facts. */
  pid_t child=fork();assert(child>=0);if(child==0){provider_facts(facts);_exit(0);}
  int status;assert(waitpid(child,&status,0)==child&&WIFEXITED(status)&&WEXITSTATUS(status)==70);
 }
 puts("software-provider-and-fd-boundaries: PASS");
}
int main(void){
 digest_vectors_fixture();
 struct profile memory_profile;
 assert(memory_profile_fixture(UINT64_C(536870912),&memory_profile)==0&&memory_profile.memory==UINT64_C(536870912));
 assert(memory_profile_fixture(UINT64_C(2147483648),&memory_profile)==0&&memory_profile.memory==UINT64_C(2147483648));
 assert(memory_profile_fixture(UINT64_C(2147483649),&memory_profile)==-1);
 assert(memory_profile_fixture(UINT64_MAX,&memory_profile)==-1);
 assert(memory_profile_fixture(0,&memory_profile)==-1);

 struct __user_cap_data_struct caps[2];memset(caps,0,sizeof(caps));assert(!has_effective_kill_capability(caps));
 caps[0].permitted=UINT32_C(1)<<CAP_KILL;caps[0].inheritable=UINT32_C(1)<<CAP_KILL;assert(!has_effective_kill_capability(caps));
 caps[0].effective=UINT32_C(1)<<CAP_CHOWN;caps[1].effective=UINT32_C(1)<<CAP_KILL;assert(!has_effective_kill_capability(caps));
 caps[0].effective|=UINT32_C(1)<<CAP_KILL;assert(has_effective_kill_capability(caps));

 unsigned char digest[32],known[32];hash_bytes("abc",3,digest);assert(hex_decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",32,known));assert(equal(digest,known,32));
 hash_bytes("",0,digest);assert(hex_decode("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",32,known));assert(equal(digest,known,32));
 struct sha256 stream;sha_init(&stream);unsigned char chunk[1000];memset(chunk,'a',sizeof(chunk));for(size_t i=0;i<1000;i++)sha_update(&stream,chunk,sizeof(chunk));sha_final(&stream,digest);assert(hex_decode("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",32,known));assert(equal(digest,known,32));
 assert(EVP_MD_fetch(hash_library,"SHA256","provider=msf-nonexistent-provider")==NULL);
 assert(identifier("scan-0"));assert(!identifier("../scan"));assert(!identifier(""));unsigned char id[16];assert(uuid_decode("00000000-0000-0000-0000-000000000001",id));assert(!uuid_decode("00000000-0000-0000-0000-00000000000Z",id));
 struct protocol s;memset(&s,0,sizeof(s));s.rx=1;s.tx=1;memcpy(s.binding.slot,"scan-0",7);unsigned char frame[145]={0};frame[1]=1;frame[3]=PREPARE;put32(frame+4,1);frame[105]=6;memcpy(frame+106,"scan-0",6);struct frame f;
 assert(decode_frame(&s,frame,144,PREPARE,&f)==0&&f.size==0);assert(decode_frame(&s,frame,144,PREPARE,&f)!=0);s.rx=1;frame[8]=1;assert(decode_frame(&s,frame,144,PREPARE,&f)!=0);frame[8]=0;frame[3]=DATA;assert(decode_frame(&s,frame,144,PREPARE,&f)!=0);frame[3]=PREPARE;s.controls=CONTROL_MAX;assert(decode_frame(&s,frame,144,PREPARE,&f)!=0);
 char *bad[]={"broker","launch","/tmp/path","00000000-0000-0000-0000-000000000001","scan-0","00000000-0000-0000-0000-000000000002",NULL};assert(broker_run(6,bad)==64);
 /* Reference currently source-staged helpers so -Werror catches their compilation, not unused noise. */
 (void)receive_frame;(void)stopped;(void)put64;(void)be64;

 struct kernel_state state;memset(&state,0,sizeof(state));state.supervisor=123;state.supervisor_ticks=456;state.mount_id=7;state.inode=8;
 unsigned char encoded[KERNEL_DISK_BYTES];kernel_encode(&state,encoded);assert(be64(encoded+176)==123);assert(be64(encoded+184)==456);
 struct kernel_state restored;assert(kernel_decode(encoded,sizeof(encoded),&restored)==0);assert(restored.supervisor==123&&restored.inode==8);
 encoded[272]=2;assert(kernel_decode(encoded,sizeof(encoded),&restored)==-1);encoded[272]=0;
 unsigned char record[FRAME_MAX+MARKER_HEADER],decoded[FRAME_MAX];size_t decoded_size;
 assert(marker_pack("PREPARED_KERNEL",encoded,sizeof(encoded),record,sizeof(record))==0);
 assert(marker_unpack("PREPARED_KERNEL",record,MARKER_HEADER+sizeof(encoded),decoded,sizeof(decoded),&decoded_size)==0&&decoded_size==sizeof(encoded));
 assert(marker_unpack("CHILD",record,MARKER_HEADER+sizeof(encoded),decoded,sizeof(decoded),&decoded_size)==-1);
 record[9]=2;assert(marker_unpack("PREPARED_KERNEL",record,MARKER_HEADER+sizeof(encoded),decoded,sizeof(decoded),&decoded_size)==-1);record[9]=1;
 record[MARKER_HEADER+10]^=1;assert(marker_unpack("PREPARED_KERNEL",record,MARKER_HEADER+sizeof(encoded),decoded,sizeof(decoded),&decoded_size)==-1);record[MARKER_HEADER+10]^=1;
 assert(marker_unpack("PREPARED_KERNEL",record,MARKER_HEADER+sizeof(encoded)-1,decoded,sizeof(decoded),&decoded_size)==-1);
 assert(marker_pack("UNKNOWN",encoded,sizeof(encoded),record,sizeof(record))==-1);
 struct binding original;memset(&original,0,sizeof(original));memcpy(original.slot,"scan.0:one",11);original.challenge[31]=255;unsigned char binding_bytes[194];size_t binding_size;
 assert(binding_encode(&original,binding_bytes,sizeof(binding_bytes),&binding_size)==0);struct binding binding_restored;assert(binding_decode(binding_bytes,binding_size,&binding_restored)==0);assert(binding_restored.challenge[31]==255);
 assert(binding_decode(binding_bytes,binding_size-1,&binding_restored)==-1);
 assert(relative_resource("usr/lib/libclamav.so.12"));assert(!relative_resource("usr/lib/../secret"));assert(!relative_resource("/usr/lib/libclamav.so"));assert(!relative_resource("scratch/code.so"));assert(identifier("scan.0:one"));
 struct profile policy_profile;memset(&policy_profile,0,sizeof(policy_profile));policy_profile.max_bytes=1024;
 char max_arg[48],scan_arg[48];char *closed_args[26];
 assert(scanner_arguments(&policy_profile,max_arg,scan_arg,closed_args)==0);
 assert(strcmp(closed_args[1],"--infected")==0&&closed_args[25]==NULL&&strcmp(closed_args[24],"-")==0);
 for(size_t i=0;closed_args[i];i++)assert(strcmp(closed_args[i],"--quiet")!=0&&strcmp(closed_args[i],"--suppress-ok-results")!=0);
 const unsigned char actual_detection[]="stdin: Eicar-Test-Signature FOUND\n";
 const unsigned char clean_output[]="stdin: OK\n";
 const unsigned char warning_output[]="WARNING: unexpected\nstdin: Eicar-Test-Signature FOUND\n";
 assert(eicar_detection(actual_detection,sizeof(actual_detection)-1));
 assert(!eicar_detection(clean_output,sizeof(clean_output)-1)&&!eicar_detection(warning_output,sizeof(warning_output)-1));
 effective_policy(&policy_profile,digest);
 unsigned char expected_policy[32];assert(hex_decode("57979520ac18c44cb593b450384e4877c3ba84626d02906edfb1941e3d3deaf2",32,expected_policy));assert(equal(digest,expected_policy,32));fputs("policy-vector-1024=",stdout);for(size_t i=0;i<32;i++)printf("%02x",digest[i]);putchar('\n');
 /* Ordinary-file fixtures exercise the same strict readback/parser, not cgroup support. */
 char swap_template[]="/tmp/msf-swap-policy-XXXXXX";char *swap_root=mkdtemp(swap_template);assert(swap_root);
 int swap_dir=open(swap_root,O_RDONLY|O_DIRECTORY|O_CLOEXEC);assert(swap_dir>=0);
 assert(require_zero_swap(swap_dir,false)==-1);assert(require_zero_swap(swap_dir,true)==-1);
 int swap_fd=openat(swap_dir,"memory.swap.max",O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);assert(swap_fd>=0);
 assert(require_zero_swap(swap_dir,true)==0);assert(require_zero_swap(swap_dir,false)==0);
 const char *bad_swap[]={"max\n","1\n","00\n","0 extra\n","0\n1\n",""};
 for(size_t i=0;i<ARRAY_LEN(bad_swap);i++){assert(ftruncate(swap_fd,0)==0);size_t n=strlen(bad_swap[i]);assert(pwrite(swap_fd,bad_swap[i],n,0)==(ssize_t)n);assert(require_zero_swap(swap_dir,false)==-1);}
 assert(ftruncate(swap_fd,0)==0);assert(pwrite(swap_fd,"0\n",2,0)==2);assert(require_zero_swap(swap_dir,false)==0);
 close(swap_fd);assert(unlinkat(swap_dir,"memory.swap.max",0)==0);close(swap_dir);assert(rmdir(swap_root)==0);
 for(unsigned int mode=0;mode<4;mode++){child_gate_fixture(mode);setup_timer_fixture(mode);}
 ordinary_protection_fixture();
 puts("native negative protocol: PASS");return 0;
}
