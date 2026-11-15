/* Baseline software-crypto diagnostics only; never scanner/containment acceptance. */
#define MSF_BROKER_TEST_INCLUDE
#include "msf-scanner-broker.c"
static void print_digest(const char *name,const unsigned char digest[32]){
 printf("%s=",name);for(size_t i=0;i<32;i++)printf("%02x",digest[i]);putchar('\n');
}
/* Diagnostics are confined to this standalone tool, never the broker protocol. */
static void diagnose_library(void){
 Dl_info image;const void *symbol=(const void *)EVP_MD_fetch;
 int found=dladdr(symbol,&image);printf("identity_dladdr=%d\n",found);
 if(!found)return;
 printf("identity_image=%s\n",image.dli_fname?image.dli_fname:"(null)");
 if(!image.dli_fname)return;
 char canonical[PATH_MAX];errno=0;
 if(!realpath(image.dli_fname,canonical)){printf("identity_realpath_errno=%d\n",errno);return;}
 printf("identity_canonical=%s\n",canonical);
 char *base=strrchr(canonical,'/');if(!base)return;
 printf("identity_basename_match=%d\n",strcmp(base+1,"libcrypto.so.3")==0);
 *base=0;errno=0;int directory=secure_directory(canonical);printf("identity_directory_ok=%d errno=%d\n",directory>=0,errno);
 if(directory<0)return;
 int fd=openat(directory,base+1,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);close(directory);printf("identity_open_ok=%d errno=%d\n",fd>=0,errno);
 if(fd<0)return;
 struct stat before,after;struct statvfs filesystem;
 if(fstat(fd,&before)!=0){printf("identity_stat_errno=%d\n",errno);close(fd);return;}
 printf("identity_stat=mode:%o uid:%llu gid:%llu nlink:%llu size:%lld dev:%u:%u inode:%llu\n",(unsigned int)before.st_mode,(unsigned long long)before.st_uid,(unsigned long long)before.st_gid,(unsigned long long)before.st_nlink,(long long)before.st_size,major(before.st_dev),minor(before.st_dev),(unsigned long long)before.st_ino);
 if(fstatvfs(fd,&filesystem)!=0){printf("identity_statvfs_errno=%d\n",errno);close(fd);return;}
 printf("identity_filesystem_readonly=%d flags=%lu\n",(filesystem.f_flag&ST_RDONLY)!=0,filesystem.f_flag);
 FILE *maps=fopen("/proc/self/maps","re");if(!maps){printf("identity_maps_errno=%d\n",errno);close(fd);return;}
 char line[PATH_MAX+256];bool matched=false;unsigned int lines=0;
 while(fgets(line,sizeof(line),maps)){
  lines++;if(!strchr(line,'\n')){printf("identity_maps_unterminated_line=%u\n",lines);break;}
  unsigned long long start,end,offset,inode;unsigned int device_major,device_minor;char permissions[5];
  if(sscanf(line,"%llx-%llx %4s %llx %x:%x %llu",&start,&end,permissions,&offset,&device_major,&device_minor,&inode)!=7){printf("identity_maps_parse_failed_line=%u\n",lines);break;}
  if((uint64_t)(uintptr_t)symbol>=start&&(uint64_t)(uintptr_t)symbol<end){
   printf("identity_map=perms:%s dev:%u:%u inode:%llu\n",permissions,device_major,device_minor,inode);matched=true;break;
  }
 }
 printf("identity_map_found=%d readerror=%d\n",matched,ferror(maps));fclose(maps);
 unsigned char digest[32];int rc=hash_fd(fd,digest);int stat_rc=fstat(fd,&after);
 printf("identity_hash_rc=%d after_stat_rc=%d same_file=%d\n",rc,stat_rc,stat_rc==0&&same_file(&before,&after));close(fd);
}
int main(int argc,char **argv){
 (void)broker_run;(void)receive_frame;(void)stopped;(void)put64;(void)be64;
 if(argc!=2)return 64;
 errno=0;int algorithm_socket=socket(AF_ALG,SOCK_SEQPACKET|SOCK_CLOEXEC,0);int socket_error=algorithm_socket<0?errno:0;
 if(algorithm_socket>=0)close(algorithm_socket);
 printf("AF_ALG_socket_errno=%d\n",socket_error);
 unsigned char digest[32],expected[32];
 hash_bytes("",0,digest);
 if(!hex_decode("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",32,expected)||!equal(digest,expected,32))return 70;
 hash_bytes("abc",3,digest);
 if(!hex_decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",32,expected)||!equal(digest,expected,32))return 70;
 struct sha256 stream;sha_init(&stream);unsigned char block[1000];memset(block,'a',sizeof(block));
 for(size_t i=0;i<1000;i++)sha_update(&stream,block,sizeof(block));
 sha_final(&stream,digest);
 if(!hex_decode("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",32,expected)||!equal(digest,expected,32))return 70;
 int fd=open(argv[1],O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return 70;
 int rc=hash_fd(fd,digest);if(close(fd)!=0||rc!=0)return 70;print_digest("file_sha256",digest);
 if(crypto_library_digest(digest)!=0){diagnose_library();return 70;}
 print_digest("mapped_libcrypto_sha256",digest);
 provider_facts(digest);print_digest("software_provider_facts_sha256",digest);
 printf("OpenSSL_runtime=%s\n",OpenSSL_version(OPENSSL_VERSION));
 puts("baseline EVP SHA256 smoke: PASS (not scanner acceptance)");return 0;
}
