#define MSF_BROKER_TEST_INCLUDE
#include "msf-scanner-broker.c"
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
int main(void){
 struct __user_cap_data_struct caps[2];memset(caps,0,sizeof(caps));assert(!has_effective_kill_capability(caps));
 caps[0].permitted=UINT32_C(1)<<CAP_KILL;caps[0].inheritable=UINT32_C(1)<<CAP_KILL;assert(!has_effective_kill_capability(caps));
 caps[0].effective=UINT32_C(1)<<CAP_CHOWN;caps[1].effective=UINT32_C(1)<<CAP_KILL;assert(!has_effective_kill_capability(caps));
 caps[0].effective|=UINT32_C(1)<<CAP_KILL;assert(has_effective_kill_capability(caps));

 unsigned char digest[32],known[32];hash_bytes("abc",3,digest);assert(hex_decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",32,known));assert(equal(digest,known,32));
 hash_bytes("",0,digest);assert(hex_decode("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",32,known));assert(equal(digest,known,32));
 struct sha256 stream;sha_init(&stream);unsigned char chunk[1000];memset(chunk,'a',sizeof(chunk));for(size_t i=0;i<1000;i++)sha_update(&stream,chunk,sizeof(chunk));sha_final(&stream,digest);assert(hex_decode("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",32,known));assert(equal(digest,known,32));
 struct sha256 unavailable_provider;assert(hash_provider(&unavailable_provider,"msf-nonexistent-sha256-provider")==-1);
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
 struct profile policy_profile;memset(&policy_profile,0,sizeof(policy_profile));policy_profile.max_bytes=1024;effective_policy(&policy_profile,digest);fputs("policy-vector-1024=",stdout);for(size_t i=0;i<32;i++)printf("%02x",digest[i]);putchar('\n');
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
