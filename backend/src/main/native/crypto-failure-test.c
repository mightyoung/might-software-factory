/* Official EVP declarations; substitutions exist only in this test translation unit. */
#define _GNU_SOURCE
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/provider.h>
#include <assert.h>
#include <errno.h>
#include <unistd.h>
static unsigned int failure;
static int test_init(uint64_t options,const OPENSSL_INIT_SETTINGS *settings){return failure==1?0:OPENSSL_init_crypto(options,settings);}
static OSSL_LIB_CTX *test_library(void){return failure==2?NULL:OSSL_LIB_CTX_new();}
static OSSL_PROVIDER *test_provider(OSSL_LIB_CTX *context,const char *name){return failure==3?NULL:OSSL_PROVIDER_load(context,name);}
static EVP_MD *test_fetch(OSSL_LIB_CTX *context,const char *name,const char *properties){return failure==4?NULL:EVP_MD_fetch(context,name,properties);}
static int test_size(const EVP_MD *algorithm){return failure==5?64:EVP_MD_get_size(algorithm);}
static const OSSL_PROVIDER *test_md_provider(const EVP_MD *algorithm){return failure==6?NULL:EVP_MD_get0_provider(algorithm);}
static const char *test_name(const OSSL_PROVIDER *provider){return failure==7?NULL:OSSL_PROVIDER_get0_name(provider);}
static EVP_MD_CTX *test_context(void){return failure==8?NULL:EVP_MD_CTX_new();}
static int test_digest_init(EVP_MD_CTX *context,const EVP_MD *algorithm,const OSSL_PARAM parameters[]){return failure==9?0:EVP_DigestInit_ex2(context,algorithm,parameters);}
static int test_update(EVP_MD_CTX *context,const void *data,size_t length){return failure==10?0:EVP_DigestUpdate(context,data,length);}
static int test_final(EVP_MD_CTX *context,unsigned char *out,unsigned int *length){
 if(failure==11)return 0;
 int rc=EVP_DigestFinal_ex(context,out,length);if(failure==12)*length=31;return rc;
}
#define OPENSSL_init_crypto test_init
#define OSSL_LIB_CTX_new test_library
#define OSSL_PROVIDER_load test_provider
#define EVP_MD_fetch test_fetch
#define EVP_MD_get_size test_size
#define EVP_MD_get0_provider test_md_provider
#define OSSL_PROVIDER_get0_name test_name
#define EVP_MD_CTX_new test_context
#define EVP_DigestInit_ex2 test_digest_init
#define EVP_DigestUpdate test_update
#define EVP_DigestFinal_ex test_final
#define MSF_BROKER_TEST_INCLUDE
#include "msf-scanner-broker.c"
int main(void){
 (void)broker_run;(void)receive_frame;(void)stopped;(void)put64;(void)be64;
 for(failure=1;failure<=12;failure++){
  int output[2];assert(pipe2(output,O_CLOEXEC)==0);pid_t child=fork();assert(child>=0);
  if(child==0){assert(close(output[0])==0&&dup2(output[1],STDOUT_FILENO)==STDOUT_FILENO&&dup2(output[1],STDERR_FILENO)==STDERR_FILENO);close(output[1]);
   unsigned char digest[32];hash_bytes("abc",3,digest);
   /* A missed error would reach an actual RESULT encoder, not merely a test flag. */
   struct protocol protocol;memset(&protocol,0,sizeof(protocol));protocol.deadline=monotonic_ms()+1000;protocol.tx=1;memcpy(protocol.binding.slot,"scan-0",7);
   (void)send_frame(&protocol,RESULT,digest,sizeof(digest));_exit(0);
  }
  assert(close(output[1])==0);unsigned char byte;assert(read(output[0],&byte,1)==0);assert(close(output[0])==0);
  int status;assert(waitpid(child,&status,0)==child&&WIFEXITED(status)&&WEXITSTATUS(status)==70);
 }
 /* An ordinary additional deny rule proves hashing never requires AF_ALG. */
 pid_t child=fork();assert(child>=0);
 if(child==0){
  struct sock_filter instructions[]={
   BPF_STMT(BPF_LD|BPF_W|BPF_ABS,(unsigned int)offsetof(struct seccomp_data,nr)),
   BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned int)SYS_socket,0,3),
   BPF_STMT(BPF_LD|BPF_W|BPF_ABS,(unsigned int)offsetof(struct seccomp_data,args[0])),
   BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_ALG,0,1),
   BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|(unsigned int)EPERM),
   BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
  struct sock_fprog program={.len=(unsigned short)ARRAY_LEN(instructions),.filter=instructions};
  assert(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)==0&&prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program)==0);
  errno=0;assert(socket(AF_ALG,SOCK_SEQPACKET|SOCK_CLOEXEC,0)==-1&&errno==EPERM);
  failure=0;unsigned char digest[32],expected[32];hash_bytes("abc",3,digest);
  assert(hex_decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",32,expected)&&equal(digest,expected,32));_exit(0);
 }
 int status;assert(waitpid(child,&status,0)==child&&WIFEXITED(status)&&WEXITSTATUS(status)==0);
 puts("crypto failures produce no RESULT: PASS (12 injected failures)");
 puts("AF_ALG denied while software SHA256 works: PASS");
 return 0;
}
