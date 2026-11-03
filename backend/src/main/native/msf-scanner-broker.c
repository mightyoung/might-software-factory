/* MSF_SCANNER_BROKER_V1. Linux only. No installation, setuid, or privilege grant in this source.
* Ordinary source parser/locking implementation; actual target acceptance is a separate gate.
* Never log profile contents, paths, scanner output, or environment to the application.
*/
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/sched.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <linux/if_alg.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <grp.h>
#include <stddef.h>
#include <dirent.h>

#define FRAME_MAX 32768U
#define CONTROL_MAX 262144U
#define DATA_MAX 30000U
#define OUTPUT_MAX 65536U
#define PROFILE_ROOT "/etc/msf-scanner/profiles"
#define REALM_ROOT "/var/lib/msf-scanner/realms"
#define CGROUP_ROOT "/sys/fs/cgroup/msf-scanner"
#define ARRAY_LEN(a) (sizeof(a)/sizeof((a)[0]))
enum frame_type { PREPARE=1,PREPARED,START,CHILD,GO,IMAGE,DATA,END,RESULT,INSPECT,QUIESCE,RECOVERY,PROBE,PROBE_RESULT,ERROR_FRAME };
/* Integrity hashing uses the existing kernel crypto SHA256 provider, never local rounds,
* a shell utility, or a linked library. Actual target acceptance must pin/probe this provider.
* There is no fallback: provider failure terminates without a successful protocol report. */
struct sha256 {int socket_fd,operation_fd;
};
static uint32_t be32(const unsigned char *p){return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|(uint32_t)p[3];
}
static uint64_t be64(const unsigned char *p){return ((uint64_t)be32(p)<<32)|be32(p+4);
}
static void put32(unsigned char *p,uint32_t x){p[0]=(unsigned char)(x>>24);
    p[1]=(unsigned char)(x>>16);
    p[2]=(unsigned char)(x>>8);
    p[3]=(unsigned char)x;
}
static void put64(unsigned char *p,uint64_t x){put32(p,(uint32_t)(x>>32));
    put32(p+4,(uint32_t)x);
}
static int hash_provider(struct sha256 *s,const char *name){
    struct sockaddr_alg address;
    memset(&address,0,sizeof(address));
    address.salg_family=AF_ALG;
    memcpy(address.salg_type,"hash",5);
    if(strlen(name)>=sizeof(address.salg_name))return -1;
    memcpy(address.salg_name,name,strlen(name)+1);
    s->socket_fd=socket(AF_ALG,SOCK_SEQPACKET|SOCK_CLOEXEC,0);
    if(s->socket_fd<0)return -1;
    if(bind(s->socket_fd,(struct sockaddr *)&address,sizeof(address))!=0){close(s->socket_fd);
        return -1;
    }
    s->operation_fd=accept4(s->socket_fd,NULL,NULL,SOCK_CLOEXEC);
    if(s->operation_fd<0){close(s->socket_fd);
        return -1;
    }return 0;
}
static void sha_init(struct sha256 *s){if(hash_provider(s,"sha256")!=0)_exit(70);
}
static void sha_update(struct sha256 *s,const void *data,size_t n){const unsigned char *p=data;
    while(n){ssize_t sent=send(s->operation_fd,p,n,MSG_MORE|MSG_NOSIGNAL);
        if(sent<0&&errno==EINTR)continue;
        if(sent<=0)_exit(70);
        p+=(size_t)sent;
        n-=(size_t)sent;
    }}
static void sha_final(struct sha256 *s,unsigned char out[32]){if(send(s->operation_fd,NULL,0,MSG_NOSIGNAL)!=0)_exit(70);
    size_t n=0;
    while(n<32){ssize_t got=read(s->operation_fd,out+n,32-n);
        if(got<0&&errno==EINTR)continue;
        if(got<=0)_exit(70);
        n+=(size_t)got;
    }close(s->operation_fd);
    close(s->socket_fd);
}
static void hash_bytes(const void *p,size_t n,unsigned char out[32]){struct sha256 s;
    sha_init(&s);
    sha_update(&s,p,n);
    sha_final(&s,out);
}
static bool equal(const unsigned char *a,const unsigned char *b,size_t n){unsigned char x=0;
    for(size_t i=0;i<n;i++)x|=(unsigned char)(a[i]^b[i]);
    return x==0;
}
static bool hex_decode(const char *s,size_t n,unsigned char *out){if(strlen(s)!=n*2)return false;
    for(size_t i=0;i<n;i++){unsigned int a=(unsigned int)(unsigned char)s[2*i],b=(unsigned int)(unsigned char)s[2*i+1];
        if(!((a>='0'&&a<='9')||(a>='a'&&a<='f'))||!((b>='0'&&b<='9')||(b>='a'&&b<='f')))return false;
        out[i]=(unsigned char)(((a<='9'?a-'0':a-'a'+10)<<4)|(b<='9'?b-'0':b-'a'+10));
    }return true;
}
static bool identifier(const char *s){size_t n=strlen(s);
    if(n<1||n>64||!((s[0]>='a'&&s[0]<='z')||(s[0]>='A'&&s[0]<='Z')||(s[0]>='0'&&s[0]<='9')))return false;
    for(size_t i=1;i<n;i++)if(!((s[i]>='a'&&s[i]<='z')||(s[i]>='A'&&s[i]<='Z')||(s[i]>='0'&&s[i]<='9')||s[i]=='_'||s[i]=='-'||s[i]=='.'||s[i]==':'))return false;
    return true;
}
static bool uuid_decode(const char *s,unsigned char out[16]){if(strlen(s)!=36)return false;
    char compact[33];
    size_t at=0;
    for(size_t i=0;i<36;i++){if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;
        }else compact[at++]=s[i];
    }compact[32]=0;
    return hex_decode(compact,16,out);
}
static uint64_t monotonic_ms(void){struct timespec t;
    if(clock_gettime(CLOCK_MONOTONIC,&t)!=0)_exit(70);
    return (uint64_t)t.tv_sec*1000+(uint64_t)t.tv_nsec/1000000;
}
static int bounded_io(int fd,void *p,size_t n,bool writing,uint64_t deadline){unsigned char *b=p;
    while(n){uint64_t now=monotonic_ms();
        if(now>=deadline){errno=ETIMEDOUT;
            return -1;
        }struct pollfd item={.fd=fd,.events=writing?POLLOUT:POLLIN};
        uint64_t left=deadline-now;
        int wait=(int)(left>1000?1000:left);
        int ready=poll(&item,1,wait);
        if(ready<0){if(errno==EINTR)continue;
            return -1;
        }if(ready==0)continue;
        ssize_t done=writing?write(fd,b,n):read(fd,b,n);
        if(done<=0){if(done<0&&(errno==EINTR||errno==EAGAIN))continue;
            return -1;
        }b+=(size_t)done;
        n-=(size_t)done;
    }return 0;
}
struct binding {unsigned char challenge[32],execution[16],realm[16],profile[32],descriptor[32];
    char slot[65];
};
struct frame {enum frame_type type;
    unsigned char body[FRAME_MAX];
    size_t size;
};
struct protocol {struct binding binding;
    uint32_t rx,tx;
    size_t controls;
    uint64_t deadline;
};
static int expect_eof(int fd,uint64_t deadline){unsigned char extra;
    for(;;){if(monotonic_ms()>=deadline)return -1;
        struct pollfd p={.fd=fd,.events=POLLIN};
        int ready=poll(&p,1,10);
        if(ready<0&&errno==EINTR)continue;
        if(ready<0)return -1;
        if(!ready)continue;
        ssize_t got=read(fd,&extra,1);
        if(got<0&&errno==EINTR)continue;
        return got==0?0:-1;
    }}
static int decode_frame(struct protocol *s,const unsigned char *p,size_t n,enum frame_type expected,struct frame *f){
    if(n<139||n>FRAME_MAX||p[0]!=0||p[1]!=1||p[2]!=0||p[3]!=(unsigned char)expected||be32(p+4)!=s->rx||s->rx==UINT32_MAX)return -1;
    if(!equal(p+8,s->binding.challenge,32)||!equal(p+40,s->binding.execution,16)||!equal(p+56,s->binding.realm,16)||!equal(p+72,s->binding.profile,32))return -1;
    size_t slot=((size_t)p[104]<<8)|p[105];
    if(slot<1||slot>64||n<138+slot||strlen(s->binding.slot)!=slot||memcmp(p+106,s->binding.slot,slot)!=0||!equal(p+106+slot,s->binding.descriptor,32))return -1;
    size_t body=n-138-slot;
    if(expected==DATA&&body>DATA_MAX)return -1;
    if(expected!=DATA){if(s->controls>CONTROL_MAX-n-4)return -1;
        s->controls+=n+4;
    }
    s->rx++;
    f->type=expected;
    f->size=body;
    memcpy(f->body,p+138+slot,body);
    return 0;
}
static int receive_frame(struct protocol *s,enum frame_type type,struct frame *f){unsigned char header[4],data[FRAME_MAX];
    if(bounded_io(STDIN_FILENO,header,4,false,s->deadline)!=0)return -1;
    uint32_t n=be32(header);
    if(n>FRAME_MAX||n<1||bounded_io(STDIN_FILENO,data,n,false,s->deadline)!=0)return -1;
    return decode_frame(s,data,n,type,f);
}
static int send_frame(struct protocol *s,enum frame_type type,const void *body,size_t size){
    unsigned char data[FRAME_MAX+4];
    size_t slot=strlen(s->binding.slot),n=138+slot+size;
    if(n>FRAME_MAX||s->tx==UINT32_MAX||slot<1||slot>64)return -1;
    if(type!=DATA){if(s->controls>CONTROL_MAX-n-4)return -1;
        s->controls+=n+4;
    }
    put32(data,(uint32_t)n);
    data[4]=0;
    data[5]=1;
    data[6]=0;
    data[7]=(unsigned char)type;
    put32(data+8,s->tx++);
    memcpy(data+12,s->binding.challenge,32);
    memcpy(data+44,s->binding.execution,16);
    memcpy(data+60,s->binding.realm,16);
    memcpy(data+76,s->binding.profile,32);
    data[108]=0;
    data[109]=(unsigned char)slot;
    memcpy(data+110,s->binding.slot,slot);
    memcpy(data+110+slot,s->binding.descriptor,32);
    if(size)memcpy(data+142+slot,body,size);
    return bounded_io(STDOUT_FILENO,data,n+4,true,s->deadline);
}
/* All privileged opens walk pinned root-owned, non-writable directory components. */
static int secure_directory(const char *path){if(path[0]!='/')return -1;
    int fd=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if(fd<0)return -1;
    char copy[PATH_MAX];
    if(strlen(path)>=sizeof(copy)){close(fd);
        return -1;
    }memcpy(copy,path,strlen(path)+1);
    char *save=NULL;
    for(char *part=strtok_r(copy,"/",&save);part;part=strtok_r(NULL,"/",&save)){if(strcmp(part,".")==0||strcmp(part,"..")==0){close(fd);
            return -1;
        }int next=openat(fd,part,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        close(fd);
        if(next<0)return -1;
        fd=next;
        struct stat st;
        if(fstat(fd,&st)!=0||st.st_uid!=0||(st.st_mode&0022)!=0){close(fd);
            return -1;
        }}return fd;
}
static int read_file_at(int dir,const char *name,unsigned char *data,size_t capacity,size_t *length){int fd=openat(dir,name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    struct stat st;
    if(fstat(fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_uid!=0||st.st_nlink!=1||(st.st_mode&0022)!=0||st.st_size<0||(uint64_t)st.st_size>capacity){close(fd);
        errno=EPROTO;
        return -1;
    }size_t n=0;
    while(n<capacity){ssize_t got=read(fd,data+n,capacity-n);
        if(got<0&&errno==EINTR)continue;
        if(got<0){close(fd);
            return -1;
        }if(got==0)break;
        n+=(size_t)got;
    }unsigned char extra;
    ssize_t tail=read(fd,&extra,1);
    struct stat after;
    int ok=tail==0&&fstat(fd,&after)==0&&st.st_ino==after.st_ino&&st.st_dev==after.st_dev&&st.st_size==after.st_size&&n==(size_t)st.st_size;
    close(fd);
    if(!ok){errno=EPROTO;
        return -1;
    }
    *length=n;
    return 0;
}

/* Native disk ABI MSFC0001: magic8,u16 version,u16 closed kind,u32 payload bytes,
* SHA256(payload)[32], then canonical payload. No native struct/padding/endian persistence. */
#define MARKER_HEADER 48U
static unsigned int marker_kind(const char *name){const char *names[]={"PREPARED","PREPARED_KERNEL","PLAN","DESCRIPTOR","START_INTENT","CHILD","GO","STOP","REAPED","RECOVERED"};
    for(size_t i=0;i<ARRAY_LEN(names);i++)if(!strcmp(name,names[i]))return (unsigned int)i+1U;
    return 0;
}
static int marker_pack(const char *name,const void *payload,size_t n,unsigned char *out,size_t capacity){unsigned int kind=marker_kind(name);
    if(!kind||n>FRAME_MAX||capacity<n+MARKER_HEADER)return -1;
    memcpy(out,"MSFC0001",8);
    out[8]=0;
    out[9]=1;
    out[10]=0;
    out[11]=(unsigned char)kind;
    put32(out+12,(uint32_t)n);
    hash_bytes(payload,n,out+16);
    memcpy(out+MARKER_HEADER,payload,n);
    return 0;
}
static int marker_unpack(const char *name,const unsigned char *raw,size_t n,unsigned char *out,size_t capacity,size_t *size){unsigned int kind=marker_kind(name);
    if(!kind||n<MARKER_HEADER||memcmp(raw,"MSFC0001",8)||raw[8]!=0||raw[9]!=1||raw[10]!=0||raw[11]!=kind)return -1;
    uint32_t length=be32(raw+12);
    if(length>FRAME_MAX||n!=MARKER_HEADER+length||length>capacity)return -1;
    unsigned char digest[32];
    hash_bytes(raw+MARKER_HEADER,length,digest);
    if(!equal(digest,raw+16,32))return -1;
    memcpy(out,raw+MARKER_HEADER,length);
    *size=length;
    return 0;
}
static int read_marker_at(int dir,const char *name,unsigned char *out,size_t capacity,size_t *size){unsigned char raw[FRAME_MAX+MARKER_HEADER];
    size_t n;
    if(read_file_at(dir,name,raw,sizeof(raw),&n)!=0)return -1;
    if(marker_unpack(name,raw,n,out,capacity,size)!=0){errno=EPROTO;
        return -1;
    }return 0;
}

static int immutable_marker(int dir,const char *name,const void *payload,size_t n){
    unsigned char expected[FRAME_MAX+MARKER_HEADER];
    if(marker_pack(name,payload,n,expected,sizeof(expected))!=0){errno=EPROTO;
        return -1;
    }size_t length=n+MARKER_HEADER;
    unsigned char existing[FRAME_MAX+MARKER_HEADER];
    size_t existing_size;
    if(read_file_at(dir,name,existing,sizeof(existing),&existing_size)==0){
        if(existing_size==length&&equal(existing,expected,length))return 0;
        if(strcmp(name,"STOP")||existing_size>=length||!equal(existing,expected,existing_size)){errno=EPROTO;
            return -1;
        }
    }else if(errno!=ENOENT)return -1;
    bool permanent=!strcmp(name,"STOP");
    char work[80];
    int rendered=snprintf(work,sizeof(work),".%s.pending",name);
    if(rendered<0||(size_t)rendered>=sizeof(work))return -1;
    const char *target=permanent?name:work;
    int fd=openat(dir,target,O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW|O_CLOEXEC,0400);
    if(fd<0){if(errno!=EEXIST)return -1;
        unsigned char partial[FRAME_MAX+MARKER_HEADER];
        size_t got;
        if(read_file_at(dir,target,partial,sizeof(partial),&got)!=0||got>length||!equal(partial,expected,got)){errno=EPROTO;
            return -1;
        }
        struct stat before,opened;
        if(fstatat(dir,target,&before,AT_SYMLINK_NOFOLLOW)!=0)return -1;
        fd=openat(dir,target,O_WRONLY|O_NOFOLLOW|O_CLOEXEC);
        if(fd<0)return -1;
        if(fstat(fd,&opened)!=0||opened.st_uid!=0||opened.st_nlink!=1||!S_ISREG(opened.st_mode)||before.st_dev!=opened.st_dev||before.st_ino!=opened.st_ino){close(fd);
            errno=EPROTO;
            return -1;
        }
    }
    int result=bounded_io(fd,expected,length,true,monotonic_ms()+5000);
    if(result==0)result=fsync(fd);
    if(close(fd)!=0)result=-1;
    // Non-STOP records have no partially visible final name. clone3 is only after fsynced START_INTENT.
    if(result==0&&!permanent&&syscall(SYS_renameat2,dir,work,dir,name,1U)!=0)result=-1;
    /* RENAME_NOREPLACE, no fallback. */
    if(result==0)result=fsync(dir);
    return result;
}
static int pinned_lock(int dir,const char *name,struct stat *identity){int fd=openat(dir,name,O_RDWR|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    struct stat st;
    if(fstat(fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_uid!=0||st.st_nlink!=1||st.st_size!=0||(st.st_mode&0077)!=0||flock(fd,LOCK_EX|LOCK_NB)!=0){close(fd);
        return -1;
    }struct stat named;
    if(fstatat(dir,name,&named,AT_SYMLINK_NOFOLLOW)!=0||st.st_dev!=named.st_dev||st.st_ino!=named.st_ino){close(fd);
        return -1;
    }*identity=st;
    return fd;
}
/* START/GO and STOP always acquire this exact per-execution inode under the global slot lock. */
static int execution_gate(int execution){int fd=openat(execution,"gate",O_CREAT|O_EXCL|O_RDWR|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd>=0){if(fsync(fd)!=0||fsync(execution)!=0){close(fd);
            return -1;
        }}else if(errno!=EEXIST)return -1;
    else fd=openat(execution,"gate",O_RDWR|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    struct stat st;
    if(fstat(fd,&st)!=0||st.st_uid!=0||!S_ISREG(st.st_mode)||st.st_nlink!=1||st.st_size!=0||flock(fd,LOCK_EX|LOCK_NB)!=0){close(fd);
        return -1;
    }return fd;
}
static bool stopped(int execution){struct stat st;
    if(fstatat(execution,"STOP",&st,AT_SYMLINK_NOFOLLOW)==0)return true;
    return errno!=ENOENT;
}
struct resource {char path[241];
    unsigned char sha256[32];
};
struct profile {struct resource resources[64];
    unsigned int resource_count;
    char id[65],realm[37],slot[65],slots[2][65];
    unsigned int slot_count;
    uid_t caller_uid,scanner_uid;
    gid_t caller_gid,scanner_gid;
    uint64_t memory,scratch,max_bytes;
    unsigned int seconds,pids,fds;
    unsigned char digest[32],scanner_digest[32];
};
static int decimal(const char *s,uint64_t *value){if(!*s||(s[0]=='0'&&s[1]))return -1;
    uint64_t n=0;
    for(size_t i=0;s[i];i++){if(s[i]<'0'||s[i]>'9'||n>(UINT64_MAX-(unsigned int)(s[i]-'0'))/10)return -1;
        n=n*10+(unsigned int)(s[i]-'0');
    }*value=n;
    return 0;
}
static bool relative_resource(const char *path){size_t n=strlen(path);
    if(n<1||n>240||path[0]=='/'||path[n-1]=='/'||strstr(path,"//"))return false;
    if(strncmp(path,"scanner/",8)&&strncmp(path,"definitions/",12)&&strncmp(path,"lib/",4)&&strncmp(path,"usr/lib/",8)&&strncmp(path,"etc/",4))return false;
    char copy[241];
    memcpy(copy,path,n+1);
    char *save=NULL;
    for(char *part=strtok_r(copy,"/",&save);part;part=strtok_r(NULL,"/",&save)){if(!strcmp(part,".")||!strcmp(part,".."))return false;
        for(size_t i=0;part[i];i++)if(!((part[i]>='A'&&part[i]<='Z')||(part[i]>='a'&&part[i]<='z')||(part[i]>='0'&&part[i]<='9')||part[i]=='_'||part[i]=='-'||part[i]=='+'||part[i]=='.'))return false;
    }return true;
}
static int load_profile(const char *id,const char *realm,const char *slot,struct profile *p){
    unsigned char expected_digest[32];
    if(!hex_decode(id,32,expected_digest))return -1;
    int root=secure_directory(PROFILE_ROOT);
    if(root<0)return -1;
    unsigned char data[FRAME_MAX];
    size_t length;
    int rc=read_file_at(root,id,data,sizeof(data)-1,&length);
    close(root);
    if(rc!=0||length==0||data[length-1]!='\n')return -1;
    hash_bytes(data,length,p->digest);
    if(!equal(expected_digest,p->digest,32))return -1;
    data[length]=0;
    const char *names[]={"format","realm-id","slot-count","caller-uid","caller-gid","scanner-uid","scanner-gid","memory-bytes","scratch-bytes","max-bytes","cpu-seconds","max-pids","max-fds","scanner-sha256","slot.0.id","slot.1.id","resource-count"};
    char *values[ARRAY_LEN(names)];
    memset(values,0,sizeof(values));
    char *resource_paths[64]={0},*resource_hashes[64]={0};
    for(size_t byte=0;byte<length;byte++)if((data[byte]!=10&&(data[byte]<32||data[byte]>126))||(data[byte]==10&&(byte==0||data[byte-1]==10)))return -1;
    char *save=NULL;
    for(char *line=strtok_r((char *)data,"\n",&save);line;line=strtok_r(NULL,"\n",&save)){
        char *split=strchr(line,'=');
        if(!split||split==line||!split[1])return -1;
        *split++=0;
        size_t i;
        for(i=0;i<ARRAY_LEN(names);i++)if(strcmp(line,names[i])==0)break;
        if(i==ARRAY_LEN(names)){
            if(strncmp(line,"resource.",9))return -1;
            char *suffix=strchr(line+9,'.');
            if(!suffix)return -1;
            *suffix++=0;
            uint64_t index;
            if(decimal(line+9,&index)!=0||index>=64)return -1;
            char **target=!strcmp(suffix,"path")?&resource_paths[index]:!strcmp(suffix,"sha256")?&resource_hashes[index]:NULL;
            if(target==NULL||*target)return -1;
            *target=split;
        }else {if(values[i])return -1;
            values[i]=split;
        }
    }
    for(size_t i=0;i<ARRAY_LEN(names);i++)if(i!=15&&!values[i])return -1;
    if(strcmp(values[0],"MSF_SCANNER_BROKER_PROFILE_V1")||strcmp(values[1],realm))return -1;
    uint64_t count;
    if(decimal(values[2],&count)!=0||count<1||count>2||!identifier(values[14])||(count==2&&(!values[15]||!identifier(values[15])||!strcmp(values[14],values[15])))||(count==1&&values[15]))return -1;
    bool found=false;
    for(size_t i=0;i<(size_t)count;i++){memcpy(p->slots[i],values[14+i],strlen(values[14+i])+1);
        if(!strcmp(slot,values[14+i]))found=true;
    }if(!found)return -1;
    p->slot_count=(unsigned int)count;
    uint64_t n[10];
    for(size_t i=0;i<10;i++)if(decimal(values[i+3],&n[i])!=0)return -1;
    if(n[0]==0||n[0]>UINT32_MAX||n[1]>UINT32_MAX||n[2]==0||n[2]>UINT32_MAX||n[3]==0||n[3]>UINT32_MAX||n[0]==n[2]||n[4]<1||n[4]>536870912||n[5]<1||n[5]>134217728||n[6]<1||n[6]>67108864||n[6]>n[5]||n[7]<1||n[7]>90||n[8]<1||n[8]>16||n[9]<3||n[9]>64||!hex_decode(values[13],32,p->scanner_digest))return -1;

    uint64_t resource_count;
    if(decimal(values[16],&resource_count)!=0||resource_count<6||resource_count>64)return -1;
    p->resource_count=(unsigned int)resource_count;
    bool executable=false,main_db=false,daily_db=false,bytecode_db=false,clamav_library=false,package=false;
    for(size_t i=0;i<64;i++){
        if(i>=resource_count){if(resource_paths[i]||resource_hashes[i])return -1;
            continue;
        }
        if(!resource_paths[i]||!resource_hashes[i]||!relative_resource(resource_paths[i])||!hex_decode(resource_hashes[i],32,p->resources[i].sha256))return -1;
        for(size_t j=0;j<i;j++)if(!strcmp(resource_paths[i],p->resources[j].path))return -1;
        memcpy(p->resources[i].path,resource_paths[i],strlen(resource_paths[i])+1);
        if(!strcmp(resource_paths[i],"scanner/clamscan")){if(!equal(p->resources[i].sha256,p->scanner_digest,32))return -1;
            executable=true;
        }
        main_db|=!strcmp(resource_paths[i],"definitions/main.cvd");
        daily_db|=!strcmp(resource_paths[i],"definitions/daily.cvd");
        bytecode_db|=!strcmp(resource_paths[i],"definitions/bytecode.cvd");
        const char *base=strrchr(resource_paths[i],'/');
        if(base){base++;
            clamav_library|=!strncmp(base,"libclamav",9);
            package|=!strncmp(base,"clamav-package",14);
        }
    }
    if(!executable||!main_db||!daily_db||!bytecode_db||!clamav_library||!package)return -1;
    if(getuid()!=(uid_t)n[0]||getgid()!=(gid_t)n[1]||geteuid()!=0)return -1;
    p->caller_uid=(uid_t)n[0];
    p->caller_gid=(gid_t)n[1];
    p->scanner_uid=(uid_t)n[2];
    p->scanner_gid=(gid_t)n[3];
    p->memory=n[4];
    p->scratch=n[5];
    p->max_bytes=n[6];
    p->seconds=(unsigned int)n[7];
    p->pids=(unsigned int)n[8];
    p->fds=(unsigned int)n[9];
    memcpy(p->id,id,strlen(id)+1);
    memcpy(p->realm,realm,37);
    memcpy(p->slot,slot,strlen(slot)+1);
    return 0;
}
struct realm {int root,slot,control,lock;
    struct stat lock_identity;
};
static void close_realm(struct realm *r){if(r->lock>=0)close(r->lock);
    if(r->control>=0)close(r->control);
    if(r->slot>=0)close(r->slot);
    if(r->root>=0)close(r->root);
}
static int open_realm(const struct profile *p,struct realm *r){
    memset(r,0xff,sizeof(*r));
    char path[PATH_MAX];
    int n=snprintf(path,sizeof(path),"%s/%s",REALM_ROOT,p->realm);
    if(n<0||(size_t)n>=sizeof(path))return -1;
    r->root=secure_directory(path);
    if(r->root<0)return -1;
    n=snprintf(path,sizeof(path),"%s/%s/slots/%s",REALM_ROOT,p->realm,p->slot);
    if(n<0||(size_t)n>=sizeof(path))return -1;
    r->slot=secure_directory(path);
    if(r->slot<0)return -1;
    r->lock=pinned_lock(r->slot,"slot.lock",&r->lock_identity);
    if(r->lock<0)return -1;
    r->control=openat(r->slot,"executions",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(r->control<0)return -1;
    struct stat st;
    if(fstat(r->control,&st)!=0||st.st_uid!=0||(st.st_mode&0077)!=0)return -1;
    /* Root-owned metadata binds the stable lock, controls, rootfs and cgroup parent identities. */
    char root_path[PATH_MAX],group_path[PATH_MAX];
    n=snprintf(root_path,sizeof(root_path),"%s/%s/slots/%s/rootfs",REALM_ROOT,p->realm,p->slot);
    if(n<0||(size_t)n>=sizeof(root_path))return -1;
    n=snprintf(group_path,sizeof(group_path),"%s/%s/%s",CGROUP_ROOT,p->realm,p->slot);
    if(n<0||(size_t)n>=sizeof(group_path))return -1;
    int root_fd=secure_directory(root_path),group_fd=secure_directory(group_path);
    struct stat root_identity,group_identity_stat;
    if(root_fd<0||group_fd<0){if(root_fd>=0)close(root_fd);
        if(group_fd>=0)close(group_fd);
        return -1;
    }
    int valid=fstat(root_fd,&root_identity)==0&&fstat(group_fd,&group_identity_stat)==0;
    close(root_fd);
    close(group_fd);
    if(!valid)return -1;
    for(size_t i=0;i<p->slot_count;i++)if(strcmp(p->slots[i],p->slot)){
        n=snprintf(root_path,sizeof(root_path),"%s/%s/slots/%s/rootfs",REALM_ROOT,p->realm,p->slots[i]);
        if(n<0||(size_t)n>=sizeof(root_path))return -1;
        root_fd=secure_directory(root_path);
        n=snprintf(group_path,sizeof(group_path),"%s/%s/%s",CGROUP_ROOT,p->realm,p->slots[i]);
        if(n<0||(size_t)n>=sizeof(group_path)){if(root_fd>=0)close(root_fd);
            return -1;
        }group_fd=secure_directory(group_path);
        struct stat other_root,other_group;
        if(root_fd<0||group_fd<0){if(root_fd>=0)close(root_fd);
            if(group_fd>=0)close(group_fd);
            return -1;
        }
        valid=fstat(root_fd,&other_root)==0&&fstat(group_fd,&other_group)==0;
        close(root_fd);
        close(group_fd);
        if(!valid||(root_identity.st_dev==other_root.st_dev&&root_identity.st_ino==other_root.st_ino)||(group_identity_stat.st_dev==other_group.st_dev&&group_identity_stat.st_ino==other_group.st_ino))return -1;
        n=snprintf(root_path,sizeof(root_path),"%s/%s/slots/%s",REALM_ROOT,p->realm,p->slots[i]);
        if(n<0||(size_t)n>=sizeof(root_path))return -1;
        int other_slot=secure_directory(root_path);
        if(other_slot<0)return -1;
        struct stat other_lock;
        int checked=fstatat(other_slot,"slot.lock",&other_lock,AT_SYMLINK_NOFOLLOW);
        close(other_slot);
        if(checked!=0||!S_ISREG(other_lock.st_mode)||other_lock.st_uid!=0||other_lock.st_nlink!=1||other_lock.st_size!=0||(other_lock.st_mode&0077)!=0||(other_lock.st_dev==r->lock_identity.st_dev&&other_lock.st_ino==r->lock_identity.st_ino))return -1;
    }

    unsigned char actual[512];
    size_t size;
    if(read_file_at(r->slot,"realm-binding",actual,sizeof(actual),&size)!=0)return -1;
    char expected[512];
    n=snprintf(expected,sizeof(expected),"MSF_PHYSICAL_REALM_V1\n%s\n%s\n%llu\n%llu\n%llu\n%llu\n%llu\n%llu\n%llu\n%llu\n",p->realm,p->slot,(unsigned long long)r->lock_identity.st_dev,(unsigned long long)r->lock_identity.st_ino,(unsigned long long)st.st_dev,(unsigned long long)st.st_ino,(unsigned long long)root_identity.st_dev,(unsigned long long)root_identity.st_ino,(unsigned long long)group_identity_stat.st_dev,(unsigned long long)group_identity_stat.st_ino);
    if(n<0||(size_t)n!=size||memcmp(actual,expected,size)!=0)return -1;
    return 0;
}
static int open_execution(struct realm *r,const char *id,bool create){if(create){if(mkdirat(r->control,id,0700)!=0)return -1;
        if(fsync(r->control)!=0)return -1;
    }int fd=openat(r->control,id,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    struct stat st;
    if(fstat(fd,&st)!=0||st.st_uid!=0||(st.st_mode&0077)!=0){close(fd);
        return -1;
    }return fd;
}
static int bootstrap_protocol(struct protocol *s,int fd,const struct profile *p,const char *execution,uint64_t deadline,enum frame_type expected,struct frame *f){
    unsigned char head[4],data[FRAME_MAX],uuid[16],realm[16];
    if(!uuid_decode(execution,uuid)||!uuid_decode(p->realm,realm))return -1;
    memset(s,0,sizeof(*s));
    s->rx=1;
    s->tx=1;
    s->deadline=deadline;
    if(bounded_io(fd,head,4,false,deadline)!=0)return -1;
    uint32_t n=be32(head);
    if(n<139||n>FRAME_MAX||bounded_io(fd,data,n,false,deadline)!=0)return -1;
    memcpy(s->binding.challenge,data+8,32);
    memcpy(s->binding.execution,uuid,16);
    memcpy(s->binding.realm,realm,16);
    memcpy(s->binding.profile,p->digest,32);
    memcpy(s->binding.slot,p->slot,strlen(p->slot)+1);
    if(expected==QUIESCE||expected==INSPECT){size_t slot_length=strlen(p->slot);
        if(n<138+slot_length)return -1;
        memcpy(s->binding.descriptor,data+106+slot_length,32);
    }
    return decode_frame(s,data,n,expected,f);
}

struct kernel_state {
    unsigned char boot[16],policy[32],control[32],lock_hash[32],stop_hash[32],report[32];
    uint64_t supervisor,supervisor_ticks,child,child_ticks,ns[5],mount_id,inode,current;
    bool populated,stopped,child_seen,image,reaped,input_done,out_done,err_done,discarded;
    unsigned char state;
};

#define KERNEL_DISK_BYTES 282U
static void kernel_encode(const struct kernel_state *k,unsigned char out[KERNEL_DISK_BYTES]){size_t at=0;
    memcpy(out+at,k->boot,16);
    at+=16;
    const unsigned char *hashes[]={k->policy,k->control,k->lock_hash,k->stop_hash,k->report};
    for(size_t i=0;i<5;i++){memcpy(out+at,hashes[i],32);
        at+=32;
    }uint64_t values[]={k->supervisor,k->supervisor_ticks,k->child,k->child_ticks,k->ns[0],k->ns[1],k->ns[2],k->ns[3],k->ns[4],k->mount_id,k->inode,k->current};
    for(size_t i=0;i<12;i++){put64(out+at,values[i]);
        at+=8;
    }bool flags[]={k->populated,k->stopped,k->child_seen,k->image,k->reaped,k->input_done,k->out_done,k->err_done,k->discarded};
    for(size_t i=0;i<9;i++)out[at++]=flags[i]?1:0;
    out[at]=k->state;
}
static int kernel_decode(const unsigned char *in,size_t size,struct kernel_state *k){if(size!=KERNEL_DISK_BYTES)return -1;
    memset(k,0,sizeof(*k));
    size_t at=0;
    memcpy(k->boot,in,16);
    at+=16;
    unsigned char *hashes[]={k->policy,k->control,k->lock_hash,k->stop_hash,k->report};
    for(size_t i=0;i<5;i++){memcpy(hashes[i],in+at,32);
        at+=32;
    }uint64_t *values[]={&k->supervisor,&k->supervisor_ticks,&k->child,&k->child_ticks,&k->ns[0],&k->ns[1],&k->ns[2],&k->ns[3],&k->ns[4],&k->mount_id,&k->inode,&k->current};
    for(size_t i=0;i<12;i++){*values[i]=be64(in+at);
        at+=8;
    }bool *flags[]={&k->populated,&k->stopped,&k->child_seen,&k->image,&k->reaped,&k->input_done,&k->out_done,&k->err_done,&k->discarded};
    for(size_t i=0;i<9;i++){if(in[at]>1)return -1;
        *flags[i]=in[at++]!=0;
    }if(in[at]>3)return -1;
    k->state=in[at];
    if(k->supervisor==0||k->supervisor_ticks==0||k->mount_id==0||k->inode==0||k->current>1000000||k->child_seen!=(k->child>0&&k->child_ticks>0))return -1;
    if(k->state==0&&(k->child_seen||k->image||k->reaped))return -1;
    if(k->state==1&&(!k->child_seen||!k->reaped||!k->input_done||!k->out_done||!k->err_done||k->discarded||k->current||k->populated))return -1;
    if(k->state==2&&(!k->stopped||!k->discarded||k->reaped||k->image||k->input_done||k->out_done||k->err_done||k->current||k->populated))return -1;
    return 0;
}
static int kernel_marker_write(int dir,const char *name,const struct kernel_state *k){unsigned char bytes[KERNEL_DISK_BYTES];
    kernel_encode(k,bytes);
    return immutable_marker(dir,name,bytes,sizeof(bytes));
}
static int kernel_marker_read(int dir,const char *name,struct kernel_state *k){unsigned char bytes[KERNEL_DISK_BYTES];
    size_t size;
    if(read_marker_at(dir,name,bytes,sizeof(bytes),&size)!=0)return -1;
    if(kernel_decode(bytes,size,k)!=0){errno=EPROTO;
        return -1;
    }return 0;
}
static int binding_encode(const struct binding *b,unsigned char *out,size_t capacity,size_t *size){size_t slot=strlen(b->slot);
    if(slot<1||slot>64||capacity<130+slot)return -1;
    memcpy(out,b->challenge,32);
    memcpy(out+32,b->execution,16);
    memcpy(out+48,b->realm,16);
    memcpy(out+64,b->profile,32);
    memcpy(out+96,b->descriptor,32);
    out[128]=0;
    out[129]=(unsigned char)slot;
    memcpy(out+130,b->slot,slot);
    *size=130+slot;
    return 0;
}
static int binding_decode(const unsigned char *in,size_t size,struct binding *b){if(size<131||in[128]!=0||in[129]<1||in[129]>64||size!=130+(size_t)in[129])return -1;
    memset(b,0,sizeof(*b));
    memcpy(b->challenge,in,32);
    memcpy(b->execution,in+32,16);
    memcpy(b->realm,in+48,16);
    memcpy(b->profile,in+64,32);
    memcpy(b->descriptor,in+96,32);
    memcpy(b->slot,in+130,in[129]);
    return identifier(b->slot)?0:-1;
}
static int binding_marker_write(int dir,const struct binding *b){unsigned char bytes[194];
    size_t size;
    if(binding_encode(b,bytes,sizeof(bytes),&size)!=0)return -1;
    return immutable_marker(dir,"PREPARED",bytes,size);
}
static void binding_hash(const struct binding *b,unsigned char out[32]){unsigned char bytes[194];
    size_t size;
    if(binding_encode(b,bytes,sizeof(bytes),&size)!=0)_exit(70);
    hash_bytes(bytes,size,out);
}
static void kernel_hash(const struct kernel_state *k,unsigned char out[32]){unsigned char bytes[KERNEL_DISK_BYTES];
    kernel_encode(k,bytes);
    hash_bytes(bytes,sizeof(bytes),out);
}
static int read_small(const char *path,char *out,size_t cap){int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0)return -1;
    size_t n=0;
    while(n<cap-1){ssize_t got=read(fd,out+n,cap-1-n);
        if(got<0&&errno==EINTR)continue;
        if(got<0){close(fd);
            return -1;
        }if(!got)break;
        n+=(size_t)got;
    }char extra;
    ssize_t tail=read(fd,&extra,1);
    close(fd);
    if(tail!=0)return -1;
    out[n]=0;
    return 0;
}
static int proc_ticks(pid_t pid,uint64_t *ticks){char path[80],text[8192];
    int n=snprintf(path,sizeof(path),"/proc/%ld/stat",(long)pid);
    if(n<0||(size_t)n>=sizeof(path)||read_small(path,text,sizeof(text))!=0)return -1;
    char *end=strrchr(text,')');
    if(!end||end[1]!=' ')return -1;
    char *save=NULL;
    unsigned int field=3;
    for(char *v=strtok_r(end+2," ",&save);v;v=strtok_r(NULL," ",&save),field++)if(field==22)return decimal(v,ticks);
    return -1;
}
static int boot_id(unsigned char out[16]){char text[64];
    if(read_small("/proc/sys/kernel/random/boot_id",text,sizeof(text))!=0)return -1;
    size_t n=strlen(text);
    if(n&&text[n-1]=='\n')text[n-1]=0;
    return uuid_decode(text,out)?0:-1;
}
static int child_namespaces(pid_t child,uint64_t out[5]){const char *names[]={"pid","mnt","net","ipc","uts"};
    for(size_t i=0;i<5;i++){char path[96],link[96];
        int n=snprintf(path,sizeof(path),"/proc/%ld/ns/%s",(long)child,names[i]);
        if(n<0||(size_t)n>=sizeof(path))return -1;
        ssize_t got=readlink(path,link,sizeof(link)-1);
        if(got<0||(size_t)got>=sizeof(link)-1)return -1;
        link[(size_t)got]=0;
        char *left=strchr(link,'['),*right=strchr(link,']');
        if(!left||!right||right[1])return -1;
        *right=0;
        if(decimal(left+1,&out[i])!=0||out[i]==0)return -1;
    }return 0;
}
static int hash_fd(int fd,unsigned char out[32]){struct sha256 hash;
    sha_init(&hash);
    unsigned char data[32768];
    uint64_t total=0;
    for(;;){ssize_t got=read(fd,data,sizeof(data));
        if(got<0&&errno==EINTR)continue;
        if(got<0)return -1;
        if(!got)break;
        total+=(uint64_t)got;
        if(total>2147483648ULL)return -1;
        sha_update(&hash,data,(size_t)got);
    }sha_final(&hash,out);
    return 0;
}

struct resource_snapshot {struct stat roots,files[64];
    unsigned char sha256[32];
    bool pinned;
};
static bool same_file(const struct stat *a,const struct stat *b){return a->st_dev==b->st_dev&&a->st_ino==b->st_ino&&a->st_size==b->st_size&&a->st_mtim.tv_sec==b->st_mtim.tv_sec&&a->st_mtim.tv_nsec==b->st_mtim.tv_nsec&&a->st_ctim.tv_sec==b->st_ctim.tv_sec&&a->st_ctim.tv_nsec==b->st_ctim.tv_nsec&&a->st_nlink==1&&b->st_nlink==1;
}
static int resource_open(int root,const char *relative){if(!relative_resource(relative))return -1;
    int fd=dup(root);
    if(fd<0)return -1;
    char copy[241];
    memcpy(copy,relative,strlen(relative)+1);
    char *save=NULL;
    char *part=strtok_r(copy,"/",&save);
    while(part){char *next=strtok_r(NULL,"/",&save);
        int opened=openat(fd,part,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|(next?O_DIRECTORY:0));
        close(fd);
        if(opened<0)return -1;
        fd=opened;
        struct stat st;
        if(fstat(fd,&st)!=0||st.st_uid!=0||(st.st_mode&0022)!=0||(next&&!S_ISDIR(st.st_mode))){close(fd);
            return -1;
        }part=next;
    }return fd;
}
static int inventory_index(const struct profile *p,const char *relative){for(size_t i=0;i<p->resource_count;i++)if(!strcmp(relative,p->resources[i].path))return (int)i;
    return -1;
}
static int resource_tree(int directory,const char *prefix,const struct profile *profile,unsigned int depth,unsigned int *entries){if(depth>16)return -1;
    int copy=openat(directory,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(copy<0)return -1;
    DIR *stream=fdopendir(copy);
    if(!stream){close(copy);
        return -1;
    }int result=-1;
    struct dirent *entry;
    errno=0;
    while((entry=readdir(stream))!=NULL){if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
        if(++*entries>1280)goto done;
        char relative[241];
        int n=snprintf(relative,sizeof(relative),"%s%s%s",prefix,*prefix?"/":"",entry->d_name);
        if(n<0||(size_t)n>=sizeof(relative))goto done;
        struct stat st;
        if(fstatat(directory,entry->d_name,&st,AT_SYMLINK_NOFOLLOW)!=0||st.st_uid!=0||(st.st_mode&0022)!=0)goto done;
        if(S_ISDIR(st.st_mode)){int child=openat(directory,entry->d_name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            if(child<0)goto done;
            unsigned int before=*entries;
            int checked=resource_tree(child,relative,profile,depth+1,entries);
            close(child);
            if(checked!=0||(!strcmp(relative,"scratch")&&before!=*entries))goto done;
        }else if(!S_ISREG(st.st_mode)||st.st_nlink!=1||(st.st_mode&0222)!=0||inventory_index(profile,relative)<0)goto done;
        errno=0;
    }if(errno)goto done;
    result=0;
    done:closedir(stream);
    return result;
}
static int verify_resources(int root,const struct profile *profile,struct resource_snapshot *snapshot,uint64_t deadline){
    struct stat root_identity;
    if(fstat(root,&root_identity)!=0||root_identity.st_uid!=0||(root_identity.st_mode&0022)!=0||!S_ISDIR(root_identity.st_mode)||(snapshot->pinned&&(root_identity.st_dev!=snapshot->roots.st_dev||root_identity.st_ino!=snapshot->roots.st_ino)))return -1;
    unsigned int entries=0;
    if(resource_tree(root,"",profile,0,&entries)!=0)return -1;
    struct sha256 inventory;
    sha_init(&inventory);
    const char inventory_domain[]="MSF_ROOTFS_RESOURCES_V1";
    sha_update(&inventory,inventory_domain,sizeof(inventory_domain)-1);
    for(size_t i=0;i<profile->resource_count;i++){
        if(monotonic_ms()>=deadline)return -1;
        int fd=resource_open(root,profile->resources[i].path);
        if(fd<0)return -1;
        struct stat before,after;
        unsigned char digest[32];
        int ok=fstat(fd,&before)==0&&S_ISREG(before.st_mode)&&before.st_uid==0&&before.st_nlink==1&&(before.st_mode&0222)==0&&before.st_size>0&&(uint64_t)before.st_size<=2147483648ULL&&hash_fd(fd,digest)==0&&fstat(fd,&after)==0&&same_file(&before,&after)&&equal(digest,profile->resources[i].sha256,32);
        close(fd);
        if(!ok||(snapshot->pinned&&!same_file(&before,&snapshot->files[i])))return -1;
        snapshot->files[i]=before;
        unsigned char scalars[56];
        put64(scalars,(uint64_t)before.st_dev);
        put64(scalars+8,(uint64_t)before.st_ino);
        put64(scalars+16,(uint64_t)before.st_size);
        put64(scalars+24,(uint64_t)before.st_mtim.tv_sec);
        put64(scalars+32,(uint64_t)before.st_mtim.tv_nsec);
        put64(scalars+40,(uint64_t)before.st_ctim.tv_sec);
        put64(scalars+48,(uint64_t)before.st_ctim.tv_nsec);
        sha_update(&inventory,profile->resources[i].path,strlen(profile->resources[i].path)+1);
        sha_update(&inventory,digest,32);
        sha_update(&inventory,scalars,sizeof(scalars));
    }
    sha_final(&inventory,snapshot->sha256);
    snapshot->roots=root_identity;
    snapshot->pinned=true;
    return 0;
}
static int verify_mappings(pid_t child,const struct profile *profile,const struct resource_snapshot *snapshot){
    char proc[80],maps[262144];
    int n=snprintf(proc,sizeof(proc),"/proc/%ld/maps",(long)child);
    if(n<0||(size_t)n>=sizeof(proc)||read_small(proc,maps,sizeof(maps))!=0)return -1;
    bool checked[64]={false};
    char *lines=NULL;
    for(char *line=strtok_r(maps,"\n",&lines);line;line=strtok_r(NULL,"\n",&lines)){
        char *save=NULL;
        char *range=strtok_r(line," ",&save),*permissions=strtok_r(NULL," ",&save);
        if(!range||strlen(range)>40||strspn(range,"0123456789abcdef-")!=strlen(range)||strchr(range,'-')==NULL||!permissions||strlen(permissions)!=4||(permissions[1]=='w'&&permissions[2]=='x'))return -1;
        if(!strtok_r(NULL," ",&save)||!strtok_r(NULL," ",&save))return -1;
        char *inode_text=strtok_r(NULL," ",&save);
        uint64_t inode;
        if(!inode_text||decimal(inode_text,&inode)!=0)return -1;
        if(inode==0)continue;
        if(save!=NULL&&strstr(save,"(deleted)"))return -1;
        int length=snprintf(proc,sizeof(proc),"/proc/%ld/map_files/%s",(long)child,range);
        if(length<0||(size_t)length>=sizeof(proc))return -1;
        int fd=open(proc,O_RDONLY|O_CLOEXEC);
        if(fd<0)return -1;
        struct stat actual;
        if(fstat(fd,&actual)!=0||!S_ISREG(actual.st_mode)||actual.st_nlink!=1){close(fd);
            return -1;
        }size_t index;
        for(index=0;index<profile->resource_count;index++)if(actual.st_dev==snapshot->files[index].st_dev&&actual.st_ino==snapshot->files[index].st_ino)break;
        if(index==profile->resource_count){close(fd);
            return -1;
        }if(!checked[index]){unsigned char digest[32];
            if(hash_fd(fd,digest)!=0||!equal(digest,profile->resources[index].sha256,32)){close(fd);
                return -1;
            }checked[index]=true;
        }close(fd);
    }return 0;
}
static int verify_image(pid_t child,const struct profile *profile,const struct resource_snapshot *snapshot){char path[80];
    int n=snprintf(path,sizeof(path),"/proc/%ld/exe",(long)child);
    if(n<0||(size_t)n>=sizeof(path))return -1;
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if(fd<0)return -1;
    unsigned char digest[32];
    struct stat identity;
    int index=inventory_index(profile,"scanner/clamscan");
    int result=index>=0&&fstat(fd,&identity)==0&&same_file(&identity,&snapshot->files[index])&&hash_fd(fd,digest)==0&&equal(digest,profile->scanner_digest,32);
    close(fd);
    return result?verify_mappings(child,profile,snapshot):-1;
}
static int control_write(int root,const char *name,const char *value){int fd=openat(root,name,O_WRONLY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    size_t n=strlen(value);
    ssize_t got=write(fd,value,n);
    int result=got>=0&&(size_t)got==n?0:-1;
    close(fd);
    return result;
}
static int cgroup_read(int root,const char *name,char *out,size_t capacity){int fd=openat(root,name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return -1;
    ssize_t got=read(fd,out,capacity-1);
    if(got<0){close(fd);
        return -1;
    }char extra;
    ssize_t tail=read(fd,&extra,1);
    close(fd);
    if(tail!=0)return -1;
    out[(size_t)got]=0;
    return 0;
}
/* No swap-budget configuration or unsupported-controller fallback. This exact fact is required
 * for both admission and retained recovery before an old policy can be compared. */
static int require_zero_swap(int group,bool create){char value[64];
    if(create&&control_write(group,"memory.swap.max","0")!=0)return -1;
    if(cgroup_read(group,"memory.swap.max",value,sizeof(value))!=0)return -1;
    return !strcmp(value,"0")||!strcmp(value,"0\n")?0:-1;
}
static int cgroup_counts(int group,struct kernel_state *k){char value[256];
    if(cgroup_read(group,"pids.current",value,sizeof(value))!=0)return -1;
    size_t n=strlen(value);
    if(n&&value[n-1]=='\n')value[n-1]=0;
    if(decimal(value,&k->current)!=0||k->current>1000000)return -1;
    if(cgroup_read(group,"cgroup.events",value,sizeof(value))!=0)return -1;
    char *p=strstr(value,"populated ");
    if(!p||(p!=value&&p[-1]!='\n')||(p[10]!='0'&&p[10]!='1')||(p[11]!='\n'&&p[11]!=0))return -1;
    k->populated=p[10]=='1';
    return 0;
}
static int group_identity(int group,struct kernel_state *k){struct statx st;
    if(statx(group,"",AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW,STATX_INO|STATX_MNT_ID,&st)!=0||(st.stx_mask&(STATX_INO|STATX_MNT_ID))!=(STATX_INO|STATX_MNT_ID)||st.stx_uid!=0||(st.stx_mode&0022)!=0)return -1;
    k->mount_id=st.stx_mnt_id;
    k->inode=st.stx_ino;
    return 0;
}
static int cgroup_counter(int group,const char *file,const char *key,uint64_t *out){char text[1024];
    if(cgroup_read(group,file,text,sizeof(text))!=0)return -1;
    char *save=NULL;
    for(char *line=strtok_r(text,"\n",&save);line;line=strtok_r(NULL,"\n",&save)){size_t length=strlen(key);
        if(!strncmp(line,key,length)&&line[length]==' ')return decimal(line+length+1,out);
    }return -1;
}
static int open_group(const struct profile *p,const char *execution,bool create,struct kernel_state *k){
    char parent[PATH_MAX];
    int n=snprintf(parent,sizeof(parent),"%s/%s/%s",CGROUP_ROOT,p->realm,p->slot);
    if(n<0||(size_t)n>=sizeof(parent))return -1;
    int root=secure_directory(parent);
    if(root<0)return -1;
    char controllers[512];
    if(cgroup_read(root,"cgroup.subtree_control",controllers,sizeof(controllers))!=0||!strstr(controllers,"pids")){close(root);
        return -1;
    }
    if(create&&mkdirat(root,execution,0700)!=0){close(root);
        return -1;
    }int group=openat(root,execution,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    close(root);
    if(group<0)return -1;
    if(group_identity(group,k)!=0){close(group);
        return -1;
    }char limit[64];
    if(create){n=snprintf(limit,sizeof(limit),"%llu",(unsigned long long)p->memory);
        if(n<0||(size_t)n>=sizeof(limit)||control_write(group,"memory.max",limit)!=0){close(group);
            return -1;
        }n=snprintf(limit,sizeof(limit),"%u",p->pids);
        if(n<0||(size_t)n>=sizeof(limit)||control_write(group,"pids.max",limit)!=0||control_write(group,"cpu.max","100000 100000")!=0){close(group);
            return -1;
        }}
    if(require_zero_swap(group,create)!=0){close(group);
        return -1;
    }
    /* Policy binds protected kernel inode plus actual immutable limits. The target acceptance gate
    * separately proves controller lifetime/release accounting and excludes foreign root migration. */
    struct sha256 hash;
    sha_init(&hash);
    sha_update(&hash,"MSF_PIDS_POLICY_V2",18);
    unsigned char identity[16];
    put64(identity,k->mount_id);
    put64(identity+8,k->inode);
    sha_update(&hash,identity,sizeof(identity));
    for(size_t i=0;i<4;i++){const char *name=i==0?"memory.max":i==1?"pids.max":i==2?"cpu.max":"memory.swap.max";
        if(cgroup_read(group,name,limit,sizeof(limit))!=0){close(group);
            return -1;
        }sha_update(&hash,name,strlen(name));
        sha_update(&hash,limit,strlen(limit));
    }sha_final(&hash,k->policy);
    return group;
}
static int hard_limit(int resource,uint64_t maximum){struct rlimit limit={.rlim_cur=(rlim_t)maximum,.rlim_max=(rlim_t)maximum};
    return setrlimit(resource,&limit);
}
static int scanner_seccomp(void){
#if defined(__x86_64__)
    const unsigned int architecture=AUDIT_ARCH_X86_64;
#elif defined(__aarch64__)
    const unsigned int architecture=AUDIT_ARCH_AARCH64;
#else
#error Unsupported broker architecture
#endif
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error Unsupported seccomp argument byte order
#endif
    /* Default allow is confined by the closed read-only rootfs and distinct UID. These syscalls
    * cannot reopen networking, mounts, namespace/root privileges, arbitrary signal or tracing. */
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,(unsigned int)offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,architecture,1,0),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,(unsigned int)offsetof(struct seccomp_data,nr)),
#define DENY_SYSCALL(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned int)(n),0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
        DENY_SYSCALL(SYS_socket),DENY_SYSCALL(SYS_prctl),DENY_SYSCALL(SYS_capset),
        DENY_SYSCALL(SYS_setuid),DENY_SYSCALL(SYS_setgid),DENY_SYSCALL(SYS_setreuid),DENY_SYSCALL(SYS_setregid),
        DENY_SYSCALL(SYS_setresuid),DENY_SYSCALL(SYS_setresgid),DENY_SYSCALL(SYS_setfsuid),DENY_SYSCALL(SYS_setfsgid),DENY_SYSCALL(SYS_setgroups),
        /* Preserve the namespace-init descendant death boundary; clone3 cannot bypass this. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,(unsigned int)offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,CLONE_NEWCGROUP|CLONE_NEWIPC|CLONE_NEWNET|CLONE_NEWNS|CLONE_NEWPID|CLONE_NEWUSER|CLONE_NEWUTS,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,(unsigned int)offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone3,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOSYS),
        DENY_SYSCALL(SYS_open_tree),DENY_SYSCALL(SYS_move_mount),DENY_SYSCALL(SYS_fsopen),DENY_SYSCALL(SYS_fsconfig),DENY_SYSCALL(SYS_fsmount),DENY_SYSCALL(SYS_pivot_root),DENY_SYSCALL(SYS_open_by_handle_at),DENY_SYSCALL(SYS_chroot),DENY_SYSCALL(SYS_mount),DENY_SYSCALL(SYS_umount2),DENY_SYSCALL(SYS_unshare),DENY_SYSCALL(SYS_setns),DENY_SYSCALL(SYS_ptrace),DENY_SYSCALL(SYS_process_vm_readv),DENY_SYSCALL(SYS_process_vm_writev),DENY_SYSCALL(SYS_bpf),DENY_SYSCALL(SYS_keyctl),DENY_SYSCALL(SYS_kill),DENY_SYSCALL(SYS_tkill),DENY_SYSCALL(SYS_tgkill),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
#undef DENY_SYSCALL
    };
    struct sock_fprog program={.len=(unsigned short)ARRAY_LEN(filter),.filter=filter};
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program);
}

static void builtin_probe(unsigned int probe,pid_t supervisor,const struct profile *profile){
    int fd=-1;
    bool denied=false;
    if(probe==2||probe==3){fd=open(probe==2?"/private/application-canary":"/private/ingestion-canary",O_RDONLY|O_CLOEXEC);
        denied=fd<0&&(errno==ENOENT||errno==EACCES);
    }
    else if(probe==4){char path[80];
        int n=snprintf(path,sizeof(path),"/proc/%ld/mem",(long)supervisor);
        if(n<0||(size_t)n>=sizeof(path))_exit(126);
        fd=open(path,O_RDONLY|O_CLOEXEC);
        denied=fd<0&&(errno==ENOENT||errno==EACCES||errno==EPERM);
    }
    else if(probe==5){fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);
        denied=fd<0&&errno==EPERM;
    }
    else if(probe==6){denied=setresuid(0,0,0)!=0&&errno==EPERM;
    }
    else if(probe==7){fd=open("/outside-scratch-probe",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
        denied=fd<0&&(errno==EROFS||errno==EACCES);
    }
    else if(probe==8){volatile unsigned char *memory=malloc((size_t)profile->memory*2);
        if(!memory)_exit(20);
        for(uint64_t at=0;at<profile->memory*2;at+=4096)memory[at]=1;
        _exit(126);
    }
    else if(probe==9){volatile uint64_t counter=0;
        for(;;)counter++;
    }
    else if(probe==10){unsigned int children=0;
        for(unsigned int i=0;i<profile->pids+2;i++){pid_t child=fork();
            if(child==0){for(;;)pause();
            }if(child<0){if(errno==EAGAIN&&children>0)_exit(20);
                _exit(126);
            }children++;
        }_exit(126);
    }
    else if(probe==11){for(unsigned int i=0;i<profile->fds+2;i++){fd=open("/scanner/clamscan",O_RDONLY|O_CLOEXEC);
            if(fd<0){if(errno==EMFILE)_exit(20);
                _exit(126);
            }}_exit(126);
    }
    else if(probe==12){
        unsigned char bytes[4096]={0};
        uint64_t total=0,per_file=profile->scratch/2;
        if(per_file<4096)per_file=4096;
        for(unsigned int file=0;file<4;file++){char name[64];
            int n=snprintf(name,sizeof(name),"/scratch/probe-%u",file);
            if(n<0||(size_t)n>=sizeof(name))_exit(126);
            fd=open(name,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
            if(fd<0){if(errno==ENOSPC)_exit(20);
                _exit(126);
            }
            for(uint64_t count=0;count<per_file;count+=sizeof(bytes)){ssize_t wrote=write(fd,bytes,sizeof(bytes));
                if(wrote<0&&errno==ENOSPC){close(fd);
                    _exit(20);
                }if(wrote!=(ssize_t)sizeof(bytes))_exit(126);
                total+=(uint64_t)wrote;
                if(total>profile->scratch+16384)_exit(126);
            }close(fd);
        }_exit(126);
    }
    else if(probe==13||probe==14||probe==15){pid_t child=fork();
        if(child<0)_exit(126);
        if(child==0){if(probe==14)_exit(0);
            for(;;)pause();
        }if(probe==14){siginfo_t dead;
            memset(&dead,0,sizeof(dead));
            if(waitid(P_PID,(id_t)child,&dead,WEXITED|WNOWAIT)!=0||dead.si_pid!=child)_exit(126);
        }/* WNOWAIT retains the zombie charge until actual release. */unsigned char ready=21;
        if(write(1,&ready,1)!=1)_exit(126);
        for(;;)pause();
    }
    if(fd>=0)close(fd);
    _exit(denied?20:126);
}
static int scanner_arguments(const struct profile *p,char max[48],char scanmax[48],char *arguments[26]){
    int n=snprintf(max,48,"--max-filesize=%llu",(unsigned long long)p->max_bytes);
    if(n<0||n>=48)return -1;
    n=snprintf(scanmax,48,"--max-scansize=%llu",(unsigned long long)p->max_bytes);
    if(n<0||n>=48)return -1;
    char *values[]={"/scanner/clamscan","--suppress-ok-results","--stdout","--no-summary","--official-db-only=yes","--bytecode=yes","--bytecode-unsigned=no","--bytecode-timeout=10000",max,scanmax,"--max-files=1","--max-recursion=1","--alert-exceeds-max=yes","--alert-encrypted=yes","--alert-macros=yes","--scan-archive=no","--scan-pdf=no","--scan-html=no","--scan-mail=no","--scan-ole2=no","--scan-pe=no","--scan-elf=no","--database=/definitions","--tempdir=/scratch","-",NULL};
    _Static_assert(ARRAY_LEN(values)==26,"Closed scanner argv changed");
    memcpy(arguments,values,sizeof(values));
    return 0;
}
static void effective_policy(const struct profile *p,unsigned char out[32]){
    char max[48],scanmax[48];
    char *arguments[26];
    if(scanner_arguments(p,max,scanmax,arguments)!=0)_exit(70);
    struct sha256 hash;
    sha_init(&hash);
    const char domain[]="MSF_CLAMSCAN_CONTAINMENT_V2\n";
    sha_update(&hash,domain,sizeof(domain)-1);
    for(size_t i=0;arguments[i];i++){sha_update(&hash,arguments[i],strlen(arguments[i]));
        sha_update(&hash,"\n",1);
    }
    const char environment[]="LC_ALL=C\nTZ=UTC\nmemory.swap.max=0\nparent-death=SIGKILL/ARMED_GO_V2\nsupervisor-effective-cap=CAP_KILL\n";
    sha_update(&hash,environment,sizeof(environment)-1);
    sha_final(&hash,out);
}
static void provider_facts(unsigned char out[32]){
    char release[256],providers[262144];
    if(read_small("/proc/sys/kernel/osrelease",release,sizeof(release))!=0||read_small("/proc/crypto",providers,sizeof(providers))!=0)_exit(70);
    struct sha256 hash;
    sha_init(&hash);
    const char domain[]="MSF_KERNEL_SHA256_PROVIDER_V1\n";
    sha_update(&hash,domain,sizeof(domain)-1);
    sha_update(&hash,release,strlen(release));
    sha_update(&hash,providers,strlen(providers));
    sha_final(&hash,out);
}
static void control_seal(const struct binding *binding,const struct profile *profile,const struct resource_snapshot *resources,unsigned char out[32]){
    unsigned char bound[32],policy[32],provider[32];
    binding_hash(binding,bound);
    effective_policy(profile,policy);
    provider_facts(provider);
    struct sha256 hash;
    sha_init(&hash);
    sha_update(&hash,"MSF_KERNEL_CONTROL_V1\n",22);
    sha_update(&hash,bound,32);
    sha_update(&hash,resources->sha256,32);
    sha_update(&hash,policy,32);
    sha_update(&hash,provider,32);
    sha_final(&hash,out);
}
enum child_gate_marker { CHILD_ARMED_V2=0xa1, CHILD_GO_V2=0xa2 };
static void setup_timeout(int signal_number){(void)signal_number;
    _exit(126);
}
static int setup_timer(uint64_t deadline){uint64_t now=monotonic_ms();
    if(now>=deadline)return -1;
    struct sigaction action;
    memset(&action,0,sizeof(action));
    action.sa_handler=setup_timeout;
    if(sigemptyset(&action.sa_mask)!=0||sigaction(SIGALRM,&action,NULL)!=0)return -1;
    sigset_t unblocked;
    if(sigemptyset(&unblocked)!=0||sigaddset(&unblocked,SIGALRM)!=0||sigprocmask(SIG_UNBLOCK,&unblocked,NULL)!=0)return -1;
    uint64_t left=deadline-now;
    struct itimerval timer;
    memset(&timer,0,sizeof(timer));
    timer.it_value.tv_sec=(time_t)(left/1000);
    timer.it_value.tv_usec=(suseconds_t)((left%1000)*1000);
    return setitimer(ITIMER_REAL,&timer,NULL);
}
static int finish_setup_timer(uint64_t deadline){sigset_t alarm_signal,pending;
    if(sigemptyset(&alarm_signal)!=0||sigaddset(&alarm_signal,SIGALRM)!=0||sigprocmask(SIG_BLOCK,&alarm_signal,NULL)!=0)return -1;
    struct itimerval disabled;
    memset(&disabled,0,sizeof(disabled));
    if(setitimer(ITIMER_REAL,&disabled,NULL)!=0||sigpending(&pending)!=0||sigismember(&pending,SIGALRM)!=0||monotonic_ms()>=deadline)return -1;
    return sigprocmask(SIG_UNBLOCK,&alarm_signal,NULL);
}
static bool has_effective_kill_capability(const struct __user_cap_data_struct capabilities[2]){
    return (capabilities[CAP_KILL/32].effective&(UINT32_C(1)<<(CAP_KILL%32)))!=0;
}
static int require_supervisor_signal_capability(void){
    /* The scanner uses a different UID but the same user namespace. Effective UID zero alone
     * does not satisfy check_kill_permission; no capability is granted or changed here. */
    struct __user_cap_header_struct header;
    struct __user_cap_data_struct capabilities[2];
    memset(&header,0,sizeof(header));
    memset(capabilities,0,sizeof(capabilities));
    header.version=_LINUX_CAPABILITY_VERSION_3;
    return syscall(SYS_capget,&header,capabilities)==0&&has_effective_kill_capability(capabilities)?0:-1;
}
static int arm_parent_death(void){int signal_number=0;
    /* Credential transitions clear this protection. No later transition/prctl is permitted by
     * seccomp, NOSUID and NO_NEW_PRIVS. The post-install ARMED->GO exchange closes the race;
     * neither numeric getppid across PID namespaces nor a pidfd poll substitutes for it. */
    return prctl(PR_SET_PDEATHSIG,SIGKILL,0,0,0)==0
        &&prctl(PR_GET_PDEATHSIG,&signal_number,0,0,0)==0&&signal_number==SIGKILL?0:-1;
}
static int armed_wait_for_go(int gate,int notify,uint64_t deadline){unsigned char marker=CHILD_ARMED_V2;
    int result=bounded_io(notify,&marker,1,true,deadline);
    close(notify);
    if(result==0)result=bounded_io(gate,&marker,1,false,deadline);
    close(gate);
    return result==0&&marker==CHILD_GO_V2?0:-1;
}
static void scanner_child(const struct profile *p,int rootfs,int in,int out,int err,int go_read,int ready_write,unsigned int probe,pid_t supervisor,uint64_t deadline){
    if(setup_timer(deadline)!=0)_exit(126);
    if(dup2(in,0)<0||dup2(out,1)<0||dup2(err,2)<0)_exit(126);
    /* Reserve the two private setup endpoints before closing every inherited lock/control fd. */
    int gate=fcntl(go_read,F_DUPFD_CLOEXEC,100),notify=fcntl(ready_write,F_DUPFD_CLOEXEC,101),root=fcntl(rootfs,F_DUPFD_CLOEXEC,102);
    if(gate<0||notify<0||root<0)_exit(126);
    if(gate!=100||notify!=101||root!=102||syscall(SYS_close_range,3U,99U,0U)!=0||syscall(SYS_close_range,103U,~0U,0U)!=0)_exit(126);
    if(mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)!=0||fchdir(root)!=0||mount(".",".",NULL,MS_BIND|MS_REC,NULL)!=0
    ||mount(NULL,".",NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV,NULL)!=0)_exit(126);
    char options[80];
    int n=snprintf(options,sizeof(options),"size=%llu,mode=0700,uid=%u,gid=%u",(unsigned long long)p->scratch,(unsigned int)p->scanner_uid,(unsigned int)p->scanner_gid);
    if(n<0||(size_t)n>=sizeof(options)||mount("tmpfs","scratch","tmpfs",MS_NOSUID|MS_NODEV|MS_NOEXEC,options)!=0||chroot(".")!=0||chdir("/")!=0)_exit(126);
    close(root);
    if(hard_limit(RLIMIT_CPU,p->seconds)!=0||hard_limit(RLIMIT_NOFILE,p->fds)!=0||hard_limit(RLIMIT_NPROC,(uint64_t)p->pids*p->slot_count)!=0||hard_limit(RLIMIT_CORE,0)!=0||hard_limit(RLIMIT_FSIZE,p->scratch)!=0)_exit(126);
    for(int capability=0;capability<=63;capability++)if(prctl(PR_CAPBSET_DROP,capability,0,0,0)!=0&&errno!=EINVAL)_exit(126);
    if(prctl(PR_CAP_AMBIENT,PR_CAP_AMBIENT_CLEAR_ALL,0,0,0)!=0)_exit(126);
    if(setgroups(0,NULL)!=0||setresgid(p->scanner_gid,p->scanner_gid,p->scanner_gid)!=0||setresuid(p->scanner_uid,p->scanner_uid,p->scanner_uid)!=0||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)!=0
        ||arm_parent_death()!=0||scanner_seccomp()!=0)_exit(126);
    /* close_range removed the inherited GO writer and every unrelated endpoint. Only this
     * single creating supervisor can acknowledge ARMED, and only after installation. */
    if(armed_wait_for_go(gate,notify,deadline)!=0)_exit(126);
    if(finish_setup_timer(deadline)!=0)_exit(126);
    if(probe>=2&&probe<=15)builtin_probe(probe,supervisor,p);
    char max[48],scanmax[48];
    char *arguments[26];
    if(scanner_arguments(p,max,scanmax,arguments)!=0)_exit(126);
    char *const environment[]={"LC_ALL=C","TZ=UTC",NULL};
    execve(arguments[0],arguments,environment);
    _exit(127);
}
static int facts_encode(const struct protocol *s,const struct kernel_state *k,unsigned char *out,size_t capacity,size_t *size){
    /* Fixed 315-byte body, nullable child represented by observed tag plus zero transport fields.
    * No fabricated nullable values escape into Java DTOs. */
    if(capacity<512)return -1;
    size_t at=0;
    memcpy(out+at,k->boot,16);
    at+=16;
    put64(out+at,k->supervisor);
    at+=8;
    put64(out+at,k->supervisor_ticks);
    at+=8;
    out[at++]=k->child_seen?1:0;
    put64(out+at,k->child);
    at+=8;
    put64(out+at,k->child_ticks);
    at+=8;
    out[at++]=k->child_seen?0:2;
    for(size_t i=0;i<5;i++){put64(out+at,k->ns[i]);
        at+=8;
    }memcpy(out+at,s->binding.execution,16);
    at+=16;
    put64(out+at,k->mount_id);
    at+=8;
    put64(out+at,k->inode);
    at+=8;
    memcpy(out+at,k->policy,32);
    at+=32;
    put64(out+at,k->current);
    at+=8;
    out[at++]=k->populated?1:0;
    out[at++]=1;
    out[at++]=1;
    out[at++]=1;
    memcpy(out+at,k->lock_hash,32);
    at+=32;
    out[at++]=k->stopped?1:0;
    memcpy(out+at,k->stop_hash,32);
    at+=32;
    out[at++]=k->child_seen?1:0;
    out[at++]=k->image?1:0;
    out[at++]=k->reaped?1:0;
    out[at++]=k->input_done?1:0;
    out[at++]=k->out_done?1:0;
    out[at++]=k->err_done?1:0;
    out[at++]=k->discarded?1:0;
    out[at++]=k->state;
    memcpy(out+at,k->control,32);
    at+=32;
    memcpy(out+at,k->report,32);
    at+=32;
    struct timespec now;
    if(clock_gettime(CLOCK_REALTIME,&now)!=0)return -1;
    put64(out+at,(uint64_t)now.tv_sec);
    at+=8;
    put32(out+at,(uint32_t)(now.tv_nsec/1000));
    at+=4;
    *size=at;
    return 0;
}
static int report_facts(struct protocol *s,enum frame_type type,struct kernel_state *k){unsigned char body[512];
    size_t size;
    if(facts_encode(s,k,body,sizeof(body),&size)!=0)return -1;
    return send_frame(s,type,body,size);
}
static int stop_execution(int execution,struct kernel_state *k){unsigned char marker[48];
    memcpy(marker,k->boot,16);
    memcpy(marker+16,k->control,32);
    if(immutable_marker(execution,"STOP",marker,sizeof(marker))!=0)return -1;
    hash_bytes(marker,sizeof(marker),k->stop_hash);
    k->stopped=true;
    return 0;
}
static int kill_group(int group,struct kernel_state *k,uint64_t deadline){if(control_write(group,"cgroup.kill","1")!=0)return -1;
    while(monotonic_ms()<deadline){if(cgroup_counts(group,k)!=0)return -1;
        if(k->current==0&&!k->populated)return 0;
        struct timespec pause={.tv_sec=0,.tv_nsec=10000000};
        if(nanosleep(&pause,NULL)!=0&&errno!=EINTR)return -1;
    }return -1;
}

static bool canonical_string(const unsigned char **cursor,const unsigned char *end,const char *text){const unsigned char *p=*cursor;
    size_t n=strlen(text);
    if((size_t)(end-p)<5+n||p[0]!=1||be32(p+1)!=n||memcmp(p+5,text,n))return false;
    *cursor=p+5+n;
    return true;
}
static bool canonical_uuid(const unsigned char **cursor,const unsigned char *end,const unsigned char expected[16]){const unsigned char *p=*cursor;
    if((size_t)(end-p)<17||p[0]!=2||!equal(p+1,expected,16))return false;
    *cursor=p+17;
    return true;
}
static bool canonical_long(const unsigned char **cursor,const unsigned char *end,uint64_t expected){const unsigned char *p=*cursor;
    if((size_t)(end-p)<9||p[0]!=3||be64(p+1)!=expected)return false;
    *cursor=p+9;
    return true;
}
static int validate_canonical_descriptor(const struct protocol *s,const unsigned char *descriptor,size_t n,const unsigned char plan[192]){
    const unsigned char *at=descriptor,*end=at+n;
    if(!canonical_string(&at,end,"INGESTION_EXECUTION_V1")||!canonical_uuid(&at,end,s->binding.execution))return -1;
    for(size_t i=0;i<7;i++)if(!canonical_uuid(&at,end,plan+i*16))return -1;
    if(!canonical_uuid(&at,end,s->binding.realm))return -1;
    char hex[65];
    for(size_t i=0;i<32;i++){static const char digits[]="0123456789abcdef";
        hex[2*i]=digits[s->binding.profile[i]>>4];
        hex[2*i+1]=digits[s->binding.profile[i]&15];
    }hex[64]=0;
    if(!canonical_string(&at,end,hex)||!canonical_string(&at,end,s->binding.slot)||!canonical_long(&at,end,be64(plan+120)))return -1;
    if((size_t)(end-at)<17||at[0]!=2)return -1;
    at+=17;
    /* immutable original claim token, opaque UUID */
    uint64_t old=be64(plan+112);
    if(old==UINT64_MAX||!canonical_long(&at,end,old+1)||(size_t)(end-at)!=13||at[0]!=4||be32(at+9)>999999999U||be32(at+9)%1000U)return -1;
    return 0;
}
static int validate_descriptor(const struct protocol *s,const struct frame *start,const unsigned char plan[192],unsigned char hash[32],uint64_t *expected,unsigned char input_hash[32],const unsigned char **descriptor,size_t *length){
    if(start->size<44)return -1;
    uint32_t n=be32(start->body);
    if(n<1||n>FRAME_MAX-512||start->size!=(size_t)n+44)return -1;
    *descriptor=start->body+4;
    *length=n;
    hash_bytes(*descriptor,n,hash);
    if(!equal(hash,s->binding.descriptor,32)||validate_canonical_descriptor(s,*descriptor,n,plan)!=0)return -1;
    *expected=be64(start->body+4+n);
    memcpy(input_hash,start->body+12+n,32);
    return 0;
}
static int receive_start(struct protocol *s,struct frame *frame){unsigned char header[4],data[FRAME_MAX];
    if(bounded_io(0,header,4,false,s->deadline)!=0)return -1;
    uint32_t n=be32(header);
    size_t slot=strlen(s->binding.slot);
    if(n<138+slot||n>FRAME_MAX||bounded_io(0,data,n,false,s->deadline)!=0)return -1;
    memcpy(s->binding.descriptor,data+106+slot,32);
    return decode_frame(s,data,n,START,frame);
}
static int actual_child_identity(pid_t child,const struct profile *p,struct kernel_state *k){
    if(proc_ticks(child,&k->child_ticks)!=0||child_namespaces(child,k->ns)!=0)return -1;
    uint64_t parent[5];
    if(child_namespaces(getpid(),parent)!=0)return -1;
    for(size_t i=0;i<5;i++)if(parent[i]==k->ns[i])return -1;
    char path[80],status[8192];
    int n=snprintf(path,sizeof(path),"/proc/%ld/status",(long)child);
    if(n<0||(size_t)n>=sizeof(path)||read_small(path,status,sizeof(status))!=0)return -1;
    const char *fields[]={"Uid:","Gid:"};
    for(size_t i=0;i<2;i++){char *line=strstr(status,fields[i]);
        unsigned int a,b,c,d;
        if(!line||sscanf(line+4,"%u %u %u %u",&a,&b,&c,&d)!=4)return -1;
        unsigned int expected=i==0?(unsigned int)p->scanner_uid:(unsigned int)p->scanner_gid;
        if(a!=expected||b!=expected||c!=expected||d!=expected)return -1;
    }
    if(!strstr(status,"NoNewPrivs:\t1\n")||!strstr(status,"Seccomp:\t2\n"))return -1;
    const char *caps[]={"CapInh:\t0000000000000000\n","CapPrm:\t0000000000000000\n","CapEff:\t0000000000000000\n","CapBnd:\t0000000000000000\n","CapAmb:\t0000000000000000\n"};
    for(size_t i=0;i<ARRAY_LEN(caps);i++)if(!strstr(status,caps[i]))return -1;
    k->child=(uint64_t)child;
    k->child_seen=true;
    k->state=3;
    return 0;
}

static int reconcile_predecessor(struct realm *realm,const char *previous,struct protocol *session,const struct kernel_state *owner);
static int set_active(struct realm *realm,const char *execution,struct protocol *session,const struct kernel_state *owner){
    unsigned char old[64];
    size_t size;
    if(read_file_at(realm->slot,"active",old,sizeof(old),&size)==0){
        if(size!=36)return -1;
        char previous[37];
        memcpy(previous,old,36);
        previous[36]=0;
        unsigned char uuid[16];
        if(!uuid_decode(previous,uuid))return -1;
        if(reconcile_predecessor(realm,previous,session,owner)!=0)return -1;
    }else if(errno!=ENOENT)return -1;
    int fd=openat(realm->slot,"active.next",O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC|O_NOFOLLOW,0400);
    if(fd<0&&errno==EEXIST){
        /* Mutable commit workfile only, never a STOP/control-history/lock inode. Under this exact
        * realm's exclusive OS lock an interrupted unpublished active.next may be rewritten. */
        fd=openat(realm->slot,"active.next",O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
        if(fd<0)return -1;
        struct stat st;
        if(fstat(fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_uid!=0||st.st_nlink!=1||st.st_size<0||st.st_size>36||(st.st_mode&0077)!=0||ftruncate(fd,0)!=0){close(fd);
            return -1;
        }
    }
    if(fd<0)return -1;
    int rc=bounded_io(fd,(void *)execution,36,true,monotonic_ms()+5000);
    if(rc==0)rc=fsync(fd);
    if(close(fd)!=0)rc=-1;
    if(rc==0)rc=renameat(realm->slot,"active.next",realm->slot,"active");
    if(rc==0)rc=fsync(realm->slot);
    return rc;
}
static int execution_capacity(int root){int duplicate=dup(root);
    if(duplicate<0)return -1;
    DIR *dir=fdopendir(duplicate);
    if(!dir){close(duplicate);
        return -1;
    }unsigned int n=0;
    struct dirent *entry;
    while((entry=readdir(dir))!=NULL){if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
        if(++n>=4096){closedir(dir);
            return -1;
        }}return closedir(dir);
}
static int nonblock(int fd){int flags=fcntl(fd,F_GETFL);
    return flags<0?-1:fcntl(fd,F_SETFL,flags|O_NONBLOCK);
}
struct drains {int out,err;
    struct sha256 out_hash,err_hash;
    uint64_t out_bytes,err_bytes;
    bool out_closed,err_closed;
    unsigned char out_data[OUTPUT_MAX],err_data[OUTPUT_MAX];
};
static int drain_one(int fd,struct sha256 *hash,uint64_t *total,bool *closed,unsigned char *retained){if(*closed)return 0;
    unsigned char buffer[4096];
    for(;;){ssize_t n=read(fd,buffer,sizeof(buffer));
        if(n<0&&(errno==EAGAIN||errno==EINTR))return 0;
        if(n<0)return -1;
        if(!n){*closed=true;
            return 0;
        }if((uint64_t)n>OUTPUT_MAX-*total)return -1;
        memcpy(retained+(size_t)*total,buffer,(size_t)n);
        *total+=(uint64_t)n;
        sha_update(hash,buffer,(size_t)n);
    }}
static int drain_available(struct drains *d){return drain_one(d->out,&d->out_hash,&d->out_bytes,&d->out_closed,d->out_data)||drain_one(d->err,&d->err_hash,&d->err_bytes,&d->err_closed,d->err_data)?-1:0;
}
static int scanner_write(int fd,const unsigned char *bytes,size_t n,struct drains *drains,uint64_t deadline){while(n){if(monotonic_ms()>=deadline||drain_available(drains)!=0)return -1;
        ssize_t wrote=write(fd,bytes,n);
        if(wrote>0){bytes+=(size_t)wrote;
            n-=(size_t)wrote;
            continue;
        }if(wrote<0&&errno!=EINTR&&errno!=EAGAIN)return -1;
        struct pollfd fds[3]={{.fd=fd,.events=POLLOUT},{.fd=drains->out,.events=POLLIN},{.fd=drains->err,.events=POLLIN}};
        if(poll(fds,3,10)<0&&errno!=EINTR)return -1;
    }return 0;
}
static void close_pipe(int pipefd[2]){if(pipefd[0]>=0)close(pipefd[0]);
    if(pipefd[1]>=0)close(pipefd[1]);
    pipefd[0]=-1;
    pipefd[1]=-1;
}
static int launch_scan(struct protocol *session,const struct profile *profile,struct realm *realm,int execution,int gate,const char *execution_id,const unsigned char plan[192],struct kernel_state *kernel){
    struct frame frame;
    if(receive_start(session,&frame)!=0)return -1;
    const unsigned char *descriptor;
    size_t descriptor_size;
    unsigned char descriptor_hash[32],expected_hash[32];
    uint64_t expected;
    if(validate_descriptor(session,&frame,plan,descriptor_hash,&expected,expected_hash,&descriptor,&descriptor_size)!=0||expected<1||expected>profile->max_bytes||stopped(execution))return -1;
    if(immutable_marker(execution,"DESCRIPTOR",descriptor,descriptor_size)!=0)return -1;
    struct kernel_state prebound=*kernel;
    int group=open_group(profile,execution_id,false,kernel);
    if(group<0)return -1;
    int result=-1;
    if(kernel->mount_id!=prebound.mount_id||kernel->inode!=prebound.inode||!equal(kernel->policy,prebound.policy,32)){close(group);
        return -1;
    }
    int input[2]={-1,-1},output[2]={-1,-1},error[2]={-1,-1},go[2]={-1,-1},ready[2]={-1,-1};
    pid_t child=-1;
    int pidfd=-1;
    int rootfs=-1;
    struct resource_snapshot resource_snapshot={0};
    rootfs=openat(realm->slot,"rootfs",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(rootfs<0||verify_resources(rootfs,profile,&resource_snapshot,session->deadline)!=0)goto cleanup;
    control_seal(&session->binding,profile,&resource_snapshot,kernel->control);
    if(cgroup_counts(group,kernel)!=0||kernel->current!=0||kernel->populated||kernel_marker_write(execution,"START_INTENT",kernel)!=0)goto cleanup;
    if(pipe2(input,O_CLOEXEC)||pipe2(output,O_CLOEXEC)||pipe2(error,O_CLOEXEC)||pipe2(go,O_CLOEXEC)||pipe2(ready,O_CLOEXEC))goto cleanup;

    struct clone_args args;
    memset(&args,0,sizeof(args));
    args.flags=CLONE_INTO_CGROUP|CLONE_PIDFD|CLONE_NEWNS|CLONE_NEWPID|CLONE_NEWNET|CLONE_NEWIPC|CLONE_NEWUTS;
    args.cgroup=(__u64)(unsigned int)group;
    args.pidfd=(__u64)(uintptr_t)&pidfd;
    args.exit_signal=SIGCHLD;
    pid_t supervisor_host=getpid();
    if(require_supervisor_signal_capability()!=0)goto cleanup;
    long cloned=syscall(SYS_clone3,&args,sizeof(args));
    if(cloned<0)goto cleanup;
    if(cloned==0)scanner_child(profile,rootfs,input[0],output[1],error[1],go[0],ready[1],UINT_MAX,supervisor_host,session->deadline);
    if(cloned>INT_MAX)goto cleanup;
    child=(pid_t)cloned;

    close(input[0]);
    input[0]=-1;
    close(output[1]);
    output[1]=-1;
    close(error[1]);
    error[1]=-1;
    close(go[0]);
    go[0]=-1;
    close(ready[1]);
    ready[1]=-1;
    unsigned char setup;
    if(bounded_io(ready[0],&setup,1,false,session->deadline)!=0||setup!=CHILD_ARMED_V2||actual_child_identity(child,profile,kernel)!=0||cgroup_counts(group,kernel)!=0||kernel->current<1||!kernel->populated)goto cleanup;
    kernel_hash(kernel,kernel->report);
    if(kernel_marker_write(execution,"CHILD",kernel)!=0||report_facts(session,CHILD,kernel)!=0)goto cleanup;
    if(receive_frame(session,GO,&frame)!=0||frame.size!=0||stopped(execution)||immutable_marker(execution,"GO",descriptor_hash,32)!=0)goto cleanup;
    if(require_supervisor_signal_capability()!=0)goto cleanup;
    unsigned char release=CHILD_GO_V2;
    if(bounded_io(go[1],&release,1,true,session->deadline)!=0)goto cleanup;
    close(go[1]);
    go[1]=-1;
    while(verify_image(child,profile,&resource_snapshot)!=0){if(monotonic_ms()>=session->deadline)goto cleanup;
        int status;
        if(waitpid(child,&status,WNOHANG)==child){child=-1;
            goto cleanup;
        }struct timespec pause={.tv_sec=0,.tv_nsec=2000000};
        (void)nanosleep(&pause,NULL);
    }
    kernel->image=true;
    kernel_hash(kernel,kernel->report);
    if(report_facts(session,IMAGE,kernel)!=0)goto cleanup;
    if(nonblock(input[1])||nonblock(output[0])||nonblock(error[0]))goto cleanup;
    struct drains drains={.out=output[0],.err=error[0]};
    sha_init(&drains.out_hash);
    sha_init(&drains.err_hash);
    struct sha256 input_hash;
    sha_init(&input_hash);
    uint64_t total=0;
    while(total<expected){if(receive_frame(session,DATA,&frame)!=0||frame.size==0||frame.size>expected-total||scanner_write(input[1],frame.body,frame.size,&drains,session->deadline)!=0)goto cleanup;
        sha_update(&input_hash,frame.body,frame.size);
        total+=(uint64_t)frame.size;
    }
    if(receive_frame(session,END,&frame)!=0||frame.size!=40||be64(frame.body)!=expected)goto cleanup;
    if(expect_eof(STDIN_FILENO,session->deadline)!=0)goto cleanup;
    unsigned char actual_hash[32];
    sha_final(&input_hash,actual_hash);
    if(!equal(actual_hash,frame.body+8,32)||!equal(actual_hash,expected_hash,32))goto cleanup;
    close(input[1]);
    input[1]=-1;
    kernel->input_done=true;
    int status=0;
    bool reaped=false;
    while(monotonic_ms()<session->deadline){if(drain_available(&drains)!=0)goto cleanup;
        if(!reaped){pid_t waited=waitpid(child,&status,WNOHANG);
            if(waited==child){reaped=true;
                child=-1;
                kernel->reaped=true;
            }else if(waited<0)goto cleanup;
        }if(reaped&&drains.out_closed&&drains.err_closed)break;
        struct pollfd fds[2]={{.fd=drains.out,.events=POLLIN},{.fd=drains.err,.events=POLLIN}};
        if(poll(fds,2,10)<0&&errno!=EINTR)goto cleanup;
    }
    if(!reaped||!drains.out_closed||!drains.err_closed||cgroup_counts(group,kernel)!=0||kernel->current!=0||kernel->populated||!WIFEXITED(status))goto cleanup;
    if(verify_resources(rootfs,profile,&resource_snapshot,session->deadline)!=0)goto cleanup;
    kernel->out_done=true;
    kernel->err_done=true;
    kernel->state=1;
    /* DTO REAPED ordinal */
    unsigned char body[640];
    int exit_code=WEXITSTATUS(status);
    put32(body,(uint32_t)exit_code);
    put64(body+4,total);
    memcpy(body+12,actual_hash,32);
    sha_final(&drains.out_hash,body+44);
    sha_final(&drains.err_hash,body+76);
    put64(body+108,drains.out_bytes);
    put64(body+116,drains.err_bytes);
    body[124]=(unsigned char)(exit_code==0?0:exit_code==1?1:2);
    hash_bytes(body,125,kernel->report);
    size_t facts_size;
    if(facts_encode(session,kernel,body+125,sizeof(body)-125,&facts_size)!=0||kernel_marker_write(execution,"REAPED",kernel)!=0||send_frame(session,RESULT,body,125+facts_size)!=0)goto cleanup;
    result=0;
    cleanup:
    if(result!=0){if(stop_execution(execution,kernel)==0){(void)control_write(group,"cgroup.kill","1");
            if(pidfd>=0)(void)syscall(SYS_pidfd_send_signal,pidfd,SIGKILL,NULL,0);
        }if(child>0){uint64_t until=monotonic_ms()+5000;
            int cleanup_status;
            while(monotonic_ms()<until){pid_t done=waitpid(child,&cleanup_status,WNOHANG);
                if(done==child||done<0)break;
                struct timespec pause={.tv_sec=0,.tv_nsec=10000000};
                (void)nanosleep(&pause,NULL);
            }}}
    close_pipe(input);
    close_pipe(output);
    close_pipe(error);
    close_pipe(go);
    close_pipe(ready);
    if(pidfd>=0)close(pidfd);
    if(rootfs>=0)close(rootfs);
    close(group);
    (void)gate;
    return result;
}

static bool eicar_detection(const unsigned char *data,size_t length){
    const char prefix[]="stdin: ",suffix[]=" FOUND\n";
    if(length<sizeof(prefix)+sizeof(suffix)||memcmp(data,prefix,sizeof(prefix)-1)||memcmp(data+length-(sizeof(suffix)-1),suffix,sizeof(suffix)-1))return false;
    size_t start=sizeof(prefix)-1,end=length-(sizeof(suffix)-1);
    bool eicar=false;
    for(size_t i=start;i<end;i++){unsigned char c=data[i];
        if(!((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c=='-'))return false;
        if(i+5<=end&&(!memcmp(data+i,"Eicar",5)||!memcmp(data+i,"EICAR",5)))eicar=true;
    }return eicar;
}
static int orphan_reaper_probe(struct protocol *session,const struct profile *profile,struct realm *realm,int execution,const char *execution_id,struct kernel_state *kernel);
static int probe_execution(struct protocol *session,const struct profile *profile,struct realm *realm,int execution,const char *execution_id,unsigned int probe,struct kernel_state *kernel){
    if(probe>15||stopped(execution))return -1;
    if(probe==15)return orphan_reaper_probe(session,profile,realm,execution,execution_id,kernel);
    struct kernel_state prebound=*kernel;
    int group=open_group(profile,execution_id,false,kernel);
    if(group<0)return -1;
    int result=-1;
    uint64_t oom_before=0,pids_before=0;
    if(cgroup_counter(group,"memory.events","oom_kill",&oom_before)!=0||cgroup_counter(group,"pids.events","max",&pids_before)!=0){close(group);
        return -1;
    }
    if(kernel->mount_id!=prebound.mount_id||kernel->inode!=prebound.inode||!equal(kernel->policy,prebound.policy,32)){close(group);
        return -1;
    }
    int input[2]={-1,-1},output[2]={-1,-1},error[2]={-1,-1},go[2]={-1,-1},ready[2]={-1,-1};
    pid_t child=-1;
    int pidfd=-1,rootfs=-1;
    struct resource_snapshot resource_snapshot={0};
    unsigned char internal[195];
    size_t internal_size;
    if(binding_encode(&session->binding,internal,sizeof(internal)-1,&internal_size)!=0)goto cleanup;
    internal[internal_size++]=(unsigned char)probe;
    rootfs=openat(realm->slot,"rootfs",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(rootfs<0||verify_resources(rootfs,profile,&resource_snapshot,session->deadline)!=0)goto cleanup;
    control_seal(&session->binding,profile,&resource_snapshot,kernel->control);
    if(immutable_marker(execution,"DESCRIPTOR",internal,internal_size)!=0||kernel_marker_write(execution,"START_INTENT",kernel)!=0)goto cleanup;
    if(pipe2(input,O_CLOEXEC)||pipe2(output,O_CLOEXEC)||pipe2(error,O_CLOEXEC)||pipe2(go,O_CLOEXEC)||pipe2(ready,O_CLOEXEC))goto cleanup;

    struct clone_args args;
    memset(&args,0,sizeof(args));
    args.flags=CLONE_INTO_CGROUP|CLONE_PIDFD|CLONE_NEWNS|CLONE_NEWPID|CLONE_NEWNET|CLONE_NEWIPC|CLONE_NEWUTS;
    args.cgroup=(__u64)(unsigned int)group;
    args.pidfd=(__u64)(uintptr_t)&pidfd;
    args.exit_signal=SIGCHLD;
    pid_t supervisor_host=getpid();
    if(require_supervisor_signal_capability()!=0)goto cleanup;
    long cloned=syscall(SYS_clone3,&args,sizeof(args));
    if(cloned<0)goto cleanup;
    if(cloned==0)scanner_child(profile,rootfs,input[0],output[1],error[1],go[0],ready[1],probe,supervisor_host,session->deadline);
    if(cloned>INT_MAX)goto cleanup;
    child=(pid_t)cloned;

    close(input[0]);
    input[0]=-1;
    close(output[1]);
    output[1]=-1;
    close(error[1]);
    error[1]=-1;
    close(go[0]);
    go[0]=-1;
    close(ready[1]);
    ready[1]=-1;
    unsigned char setup;
    if(bounded_io(ready[0],&setup,1,false,session->deadline)!=0||setup!=CHILD_ARMED_V2||actual_child_identity(child,profile,kernel)!=0||kernel_marker_write(execution,"CHILD",kernel)!=0||stopped(execution))goto cleanup;
    if(immutable_marker(execution,"GO",kernel->control,32)!=0)goto cleanup;
    if(require_supervisor_signal_capability()!=0)goto cleanup;
    unsigned char release=CHILD_GO_V2;
    if(bounded_io(go[1],&release,1,true,session->deadline)!=0)goto cleanup;
    close(go[1]);
    go[1]=-1;
    static const unsigned char clean[]="MSF scanner clean generation control\n";
    static const unsigned char eicar[]="X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    const unsigned char *bytes=probe==0?clean:probe==1?eicar:(const unsigned char *)"";
    size_t count=probe==0?sizeof(clean)-1:probe==1?sizeof(eicar)-1:0;
    if(nonblock(input[1])||nonblock(output[0])||nonblock(error[0]))goto cleanup;
    struct drains drains={.out=output[0],.err=error[0]};
    struct sha256 evidence;
    sha_init(&evidence);
    unsigned char probe_tag=(unsigned char)probe;
    sha_update(&evidence,&probe_tag,1);
    sha_update(&evidence,kernel->control,32);
    sha_init(&drains.out_hash);
    sha_init(&drains.err_hash);
    if(probe<2){while(verify_image(child,profile,&resource_snapshot)!=0){if(monotonic_ms()>=session->deadline)goto cleanup;
            struct timespec pause={.tv_sec=0,.tv_nsec=2000000};
            (void)nanosleep(&pause,NULL);
        }kernel->image=true;
        if(scanner_write(input[1],bytes,count,&drains,session->deadline)!=0)goto cleanup;
    }
    close(input[1]);
    input[1]=-1;
    int status=0;
    bool reaped=false,accounting_seen=false;
    struct rusage usage;
    memset(&usage,0,sizeof(usage));
    while(monotonic_ms()<session->deadline){
        if(drain_available(&drains)!=0)goto cleanup;
        if(probe>=13&&drains.out_bytes==1&&!accounting_seen){if(drains.out_data[0]!=21)goto cleanup;
            if(cgroup_counts(group,kernel)!=0||kernel->current<2||!kernel->populated)goto cleanup;
            accounting_seen=true;
            unsigned char charged[8];
            put64(charged,kernel->current);
            sha_update(&evidence,charged,sizeof(charged));
            if(control_write(group,"cgroup.kill","1")!=0)goto cleanup;
        }
        if(!reaped){pid_t done=wait4(child,&status,WNOHANG,&usage);
            if(done==child){reaped=true;
                child=-1;
            }else if(done<0)goto cleanup;
        }
        if(reaped){if(probe==10&&(control_write(group,"cgroup.kill","1")!=0))goto cleanup;
            if(cgroup_counts(group,kernel)!=0)goto cleanup;
            if(drains.out_closed&&drains.err_closed&&kernel->current==0&&!kernel->populated)break;
        }
        struct timespec pause={.tv_sec=0,.tv_nsec=10000000};
        (void)nanosleep(&pause,NULL);
    }
    if(!reaped||!drains.out_closed||!drains.err_closed||kernel->current!=0||kernel->populated)goto cleanup;
    bool passed=false;
    int code=WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
    if(probe==0)passed=WIFEXITED(status)&&code==0&&drains.out_bytes==0&&drains.err_bytes==0;
    else if(probe==1)passed=WIFEXITED(status)&&code==1&&eicar_detection(drains.out_data,(size_t)drains.out_bytes)&&drains.err_bytes==0;
    else if(probe==8){uint64_t oom_after;
        if(cgroup_counter(group,"memory.events","oom_kill",&oom_after)!=0)goto cleanup;
        passed=WIFSIGNALED(status)&&WTERMSIG(status)==SIGKILL&&oom_after>oom_before;
        unsigned char counter_bytes[8];
        put64(counter_bytes,oom_after);
        sha_update(&evidence,counter_bytes,8);
    }
    else if(probe==9){uint64_t cpu=(uint64_t)usage.ru_utime.tv_sec*1000000+(uint64_t)usage.ru_utime.tv_usec+(uint64_t)usage.ru_stime.tv_sec*1000000+(uint64_t)usage.ru_stime.tv_usec;
        passed=WIFSIGNALED(status)&&(WTERMSIG(status)==SIGKILL||WTERMSIG(status)==SIGXCPU)&&cpu+100000>=(uint64_t)profile->seconds*1000000;
        unsigned char observed[8];
        put64(observed,cpu);
        sha_update(&evidence,observed,8);
    }
    else if(probe==10){uint64_t pids_after;
        if(cgroup_counter(group,"pids.events","max",&pids_after)!=0)goto cleanup;
        passed=WIFEXITED(status)&&code==20&&pids_after>pids_before;
        unsigned char counter_bytes[8];
        put64(counter_bytes,pids_after);
        sha_update(&evidence,counter_bytes,8);
    }
    else if(probe>=13)passed=accounting_seen&&WIFSIGNALED(status)&&WTERMSIG(status)==SIGKILL;
    else passed=WIFEXITED(status)&&code==20;
    if(!passed||verify_resources(rootfs,profile,&resource_snapshot,session->deadline)!=0)goto cleanup;
    kernel->reaped=true;
    kernel->out_done=true;
    kernel->err_done=true;
    kernel->input_done=true;
    kernel->state=1;
    unsigned char body[109];
    body[0]=0;
    put32(body+1,(uint32_t)(probe<2?code:0));
    put64(body+5,(uint64_t)count);
    hash_bytes(bytes,count,body+13);
    unsigned char out_hash[32],err_hash[32],state_hash[32],exit_bytes[4];
    sha_final(&drains.out_hash,out_hash);
    sha_final(&drains.err_hash,err_hash);
    kernel_hash(kernel,state_hash);
    put32(exit_bytes,(uint32_t)code);
    sha_update(&evidence,body+13,32);
    sha_update(&evidence,out_hash,32);
    sha_update(&evidence,err_hash,32);
    sha_update(&evidence,state_hash,32);
    sha_update(&evidence,exit_bytes,4);
    sha_final(&evidence,body+45);
    effective_policy(profile,body+77);
    if(kernel_marker_write(execution,"REAPED",kernel)!=0||send_frame(session,PROBE_RESULT,body,sizeof(body))!=0)goto cleanup;
    result=0;
    cleanup:
    if(result!=0){(void)stop_execution(execution,kernel);
        (void)control_write(group,"cgroup.kill","1");
        if(pidfd>=0)(void)syscall(SYS_pidfd_send_signal,pidfd,SIGKILL,NULL,0);
        if(child>0){uint64_t until=monotonic_ms()+5000;
            int cleanup_status;
            while(monotonic_ms()<until){pid_t done=waitpid(child,&cleanup_status,WNOHANG);
                if(done==child||done<0)break;
                struct timespec pause={.tv_sec=0,.tv_nsec=10000000};
                (void)nanosleep(&pause,NULL);
            }}}
    close_pipe(input);
    close_pipe(output);
    close_pipe(error);
    close_pipe(go);
    close_pipe(ready);
    if(pidfd>=0)close(pidfd);
    if(rootfs>=0)close(rootfs);
    close(group);
    return result;
}
static int recover_execution(struct protocol *session,const struct profile *profile,int execution,const char *execution_id,struct kernel_state *kernel,bool emit){
    struct kernel_state original;
    bool started=kernel_marker_read(execution,"START_INTENT",&original)==0;
    if(!started){if(errno!=ENOENT||kernel_marker_read(execution,"PREPARED_KERNEL",&original)!=0)return -1;
    }
    if(!equal(original.boot,kernel->boot,16))return -1;
    memcpy(kernel->control,original.control,32);
    if(stop_execution(execution,kernel)!=0)return -1;
    struct kernel_state observed=*kernel;
    int group=open_group(profile,execution_id,false,&observed);
    if(group<0)return -1;
    int rc=-1;
    if(observed.mount_id!=original.mount_id||observed.inode!=original.inode||!equal(observed.policy,original.policy,32))goto done;
    if(kill_group(group,&observed,session->deadline)!=0)goto done;
    kernel->mount_id=observed.mount_id;
    kernel->inode=observed.inode;
    memcpy(kernel->policy,observed.policy,32);
    kernel->current=observed.current;
    kernel->populated=observed.populated;
    kernel->state=started?2:0;
    kernel->discarded=started;
    /* Slot lock is held exclusively, prior supervisor exclusively owned every endpoint, protected
    * pids release accounting proves no execution task retains descriptors; output is discarded. */
    kernel_hash(kernel,kernel->report);
    if(immutable_marker(execution,"RECOVERED",kernel->stop_hash,32)!=0||(emit&&report_facts(session,RECOVERY,kernel)!=0))goto done;
    rc=0;
    done:close(group);
    return rc;
}



static int orphan_reaper_probe(struct protocol *session,const struct profile *profile,struct realm *realm,int execution,const char *execution_id,struct kernel_state *kernel){
    int subreaper=0;
    if(prctl(PR_GET_CHILD_SUBREAPER,&subreaper)!=0||subreaper!=0)return -1;
    int group=open_group(profile,execution_id,false,kernel);
    if(group<0)return -1;
    int result=-1;
    int notification[2]={-1,-1};
    pid_t owner=-1;
    int resource_root=openat(realm->slot,"rootfs",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    struct resource_snapshot resource_snapshot={0};
    if(resource_root<0){close(group);
        return -1;
    }
    if(verify_resources(resource_root,profile,&resource_snapshot,session->deadline)!=0){close(resource_root);
        close(group);
        return -1;
    }control_seal(&session->binding,profile,&resource_snapshot,kernel->control);
    if(binding_marker_write(execution,&session->binding)!=0||kernel_marker_write(execution,"START_INTENT",kernel)!=0||pipe2(notification,O_CLOEXEC)!=0)goto done;
    owner=fork();
    if(owner<0)goto done;
    if(owner==0){
        /* Bounded fault injector, not a daemon/reaper. It alone owns every scanner endpoint and
        * retains the inherited slot flock until deliberately killed before durable CHILD. */
        (void)alarm(profile->seconds+10U);
        close(notification[0]);
        int input[2],output[2],error[2],go[2],ready[2];
        if(pipe2(input,O_CLOEXEC)||pipe2(output,O_CLOEXEC)||pipe2(error,O_CLOEXEC)||pipe2(go,O_CLOEXEC)||pipe2(ready,O_CLOEXEC))_exit(126);
        int rootfs=openat(realm->slot,"rootfs",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(rootfs<0)_exit(126);
        int pidfd=-1;
        struct clone_args args;
        memset(&args,0,sizeof(args));
        args.flags=CLONE_INTO_CGROUP|CLONE_PIDFD|CLONE_NEWNS|CLONE_NEWPID|CLONE_NEWNET|CLONE_NEWIPC|CLONE_NEWUTS;
        args.cgroup=(__u64)(unsigned int)group;
        args.pidfd=(__u64)(uintptr_t)&pidfd;
        args.exit_signal=SIGCHLD;
        pid_t supervisor_host=getpid();
        if(require_supervisor_signal_capability()!=0)_exit(126);
        long cloned=syscall(SYS_clone3,&args,sizeof(args));
        if(cloned<0||cloned>INT_MAX)_exit(126);
        if(cloned==0)scanner_child(profile,rootfs,input[0],output[1],error[1],go[0],ready[1],UINT_MAX,supervisor_host,session->deadline);
        uint64_t ticks;
        if(proc_ticks((pid_t)cloned,&ticks)!=0)_exit(126);
        unsigned char report[16];
        put64(report,(uint64_t)cloned);
        put64(report+8,ticks);
        if(bounded_io(notification[1],report,sizeof(report),true,session->deadline)!=0)_exit(126);
        for(;;)pause();
        /* No CHILD marker or GO. Observer kills this exact fork child. */
    }
    close(notification[1]);
    notification[1]=-1;
    unsigned char report[16];
    if(bounded_io(notification[0],report,sizeof(report),false,session->deadline)!=0||be64(report)==0||be64(report+8)==0||cgroup_counts(group,kernel)!=0||kernel->current==0)goto done;
    /* Drop the observer's copy: last-close lock lifetime must belong to the actual pipe owner. */
    close(realm->lock);
    realm->lock=-1;
    if(kill(owner,SIGKILL)!=0)goto done;
    bool reaped=false;
    while(monotonic_ms()<session->deadline){int status;
        pid_t waited=waitpid(owner,&status,WNOHANG);
        if(waited==owner){if(!WIFSIGNALED(status)||WTERMSIG(status)!=SIGKILL)goto done;
            reaped=true;
            owner=-1;
            break;
        }if(waited<0)goto done;
        struct timespec pause={.tv_sec=0,.tv_nsec=10000000};
        (void)nanosleep(&pause,NULL);
    }
    if(!reaped)goto done;
    struct stat reacquired;
    realm->lock=pinned_lock(realm->slot,"slot.lock",&reacquired);
    if(realm->lock<0||reacquired.st_dev!=realm->lock_identity.st_dev||reacquired.st_ino!=realm->lock_identity.st_ino)goto done;
    /* This invokes the production missing-CHILD path. It may not waitpid the orphan child:
    * zero pids.current therefore depends on the target's EXISTING orphan reaper. */
    if(recover_execution(session,profile,execution,execution_id,kernel,false)!=0||kernel->state!=2||kernel->child_seen||kernel->reaped||kernel->input_done||kernel->out_done||kernel->err_done||!kernel->discarded)goto done;
    unsigned char body[109];
    body[0]=0;
    put32(body+1,0);
    put64(body+5,0);
    hash_bytes("",0,body+13);
    kernel_hash(kernel,body+45);
    effective_policy(profile,body+77);
    if(verify_resources(resource_root,profile,&resource_snapshot,session->deadline)!=0||send_frame(session,PROBE_RESULT,body,sizeof(body))!=0)goto done;
    result=0;
    done:
    if(owner>0){(void)kill(owner,SIGKILL);
        uint64_t until=monotonic_ms()+5000;
        while(monotonic_ms()<until){int status;
            pid_t waited=waitpid(owner,&status,WNOHANG);
            if(waited==owner||waited<0)break;
            struct timespec pause={.tv_sec=0,.tv_nsec=10000000};
            (void)nanosleep(&pause,NULL);
        }}
    if(result!=0){(void)stop_execution(execution,kernel);
        (void)control_write(group,"cgroup.kill","1");
    }
    close(resource_root);
    close_pipe(notification);
    close(group);
    return result;
}
static int reconcile_predecessor(struct realm *realm,const char *previous,struct protocol *session,const struct kernel_state *owner){
    int execution=open_execution(realm,previous,false);
    if(execution<0)return -1;
    int gate=execution_gate(execution);
    if(gate<0){close(execution);
        return -1;
    }
    int result=-1;
    struct kernel_state reaped_state;
    /* A REAPED marker is produced only after actual wait, zero tasks and all drains. */
    if(kernel_marker_read(execution,"REAPED",&reaped_state)==0){
        result=0;
        goto done;
    }
    if(errno!=ENOENT)goto done;
    struct binding recorded;
    unsigned char binding_bytes[194];
    size_t binding_size;
    if(read_marker_at(execution,"PREPARED",binding_bytes,sizeof(binding_bytes),&binding_size)!=0||binding_decode(binding_bytes,binding_size,&recorded)!=0)goto done;
    unsigned char id[16];
    if(!uuid_decode(previous,id)||!equal(id,recorded.execution,16)||!equal(recorded.realm,session->binding.realm,16)||!memchr(recorded.slot,0,sizeof(recorded.slot))||strcmp(recorded.slot,session->binding.slot))goto done;
    char old_profile_id[65];
    for(size_t i=0;i<32;i++){static const char hex[]="0123456789abcdef";
        old_profile_id[2*i]=hex[recorded.profile[i]>>4];
        old_profile_id[2*i+1]=hex[recorded.profile[i]&15];
    }old_profile_id[64]=0;
    struct profile old_profile;
    memset(&old_profile,0,sizeof(old_profile));
    char realm_id[37];
    /* Realm CLI spelling is already canonical and physically pinned; original profile may be old. */
    char current_realm[37];
    size_t at=0;
    static const char hex[]="0123456789abcdef";
    for(size_t i=0;i<16;i++){if(i==4||i==6||i==8||i==10)current_realm[at++]='-';
        current_realm[at++]=hex[recorded.realm[i]>>4];
        current_realm[at++]=hex[recorded.realm[i]&15];
    }current_realm[at]=0;
    memcpy(realm_id,current_realm,sizeof(realm_id));
    if(load_profile(old_profile_id,realm_id,recorded.slot,&old_profile)!=0)goto done;
    struct protocol recovery=*session;
    recovery.binding=recorded;
    memcpy(recovery.binding.challenge,session->binding.challenge,32);
    unsigned char descriptor[FRAME_MAX];
    size_t descriptor_size;
    if(read_marker_at(execution,"DESCRIPTOR",descriptor,sizeof(descriptor),&descriptor_size)==0)hash_bytes(descriptor,descriptor_size,recovery.binding.descriptor);
    else if(errno!=ENOENT)goto done;
    struct kernel_state kernel=*owner;
    kernel.child=0;
    kernel.child_ticks=0;
    memset(kernel.ns,0,sizeof(kernel.ns));
    kernel.child_seen=false;
    kernel.image=false;
    kernel.reaped=false;
    kernel.input_done=false;
    kernel.out_done=false;
    kernel.err_done=false;
    kernel.discarded=false;
    kernel.stopped=false;
    kernel.state=3;
    result=recover_execution(&recovery,&old_profile,execution,previous,&kernel,false);
    done:close(gate);
    close(execution);
    return result;
}
static int recovery_descriptor(struct protocol *session,int execution,const struct frame *request,bool persist){
    unsigned char stored[FRAME_MAX];
    size_t size;
    bool exists=read_marker_at(execution,"DESCRIPTOR",stored,sizeof(stored),&size)==0;
    if(!exists&&errno!=ENOENT)return -1;
    if(request->size<1||request->body[0]>1)return -1;
    if(request->body[0]==1){if(request->size<5||be32(request->body+1)!=(uint32_t)(request->size-5))return -1;
        const unsigned char *descriptor=request->body+5;
        size_t n=request->size-5;
        unsigned char digest[32];
        hash_bytes(descriptor,n,digest);
        if(!equal(digest,session->binding.descriptor,32))return -1;
        if(exists){if(size!=n||!equal(stored,descriptor,n))return -1;
        }
        else {unsigned char plan[192];
            size_t plan_size;
            if(read_marker_at(execution,"PLAN",plan,sizeof(plan),&plan_size)!=0||plan_size!=sizeof(plan)||validate_canonical_descriptor(session,descriptor,n,plan)!=0||(persist&&immutable_marker(execution,"DESCRIPTOR",descriptor,n)!=0))return -1;
        }
    }else {if(request->size!=1)return -1;
        if(exists){unsigned char digest[32];
            hash_bytes(stored,size,digest);
            if(!equal(digest,session->binding.descriptor,32))return -1;
        }else {unsigned char zero[32]={0};
            if(!equal(zero,session->binding.descriptor,32))return -1;
        }}
    return 0;
}
static int inspect_execution(struct protocol *session,const struct profile *profile,int execution,const char *execution_id,struct kernel_state *kernel){
    struct kernel_state original;
    if(kernel_marker_read(execution,"START_INTENT",&original)!=0){if(errno!=ENOENT||kernel_marker_read(execution,"PREPARED_KERNEL",&original)!=0)return -1;
    }
    if(!equal(original.boot,kernel->boot,16))return -1;
    int group=open_group(profile,execution_id,false,kernel);
    if(group<0)return -1;
    int rc=-1;
    if(original.mount_id!=kernel->mount_id||original.inode!=kernel->inode||!equal(original.policy,kernel->policy,32)||cgroup_counts(group,kernel)!=0)goto done;
    memcpy(kernel->control,original.control,32);
    kernel->state=3;
    if(stopped(execution)){unsigned char stop[48];
        size_t length;
        if(read_marker_at(execution,"STOP",stop,sizeof(stop),&length)!=0||length!=48||!equal(stop,kernel->boot,16)||!equal(stop+16,kernel->control,32))goto done;
        kernel->stopped=true;
        hash_bytes(stop,48,kernel->stop_hash);
    }
    kernel_hash(kernel,kernel->report);
    rc=report_facts(session,RECOVERY,kernel);
    done:close(group);
    return rc;
}
static void lock_hash(const struct stat *identity,unsigned char out[32]){unsigned char bytes[16];
    put64(bytes,(uint64_t)identity->st_dev);
    put64(bytes+8,(uint64_t)identity->st_ino);
    hash_bytes(bytes,sizeof(bytes),out);
}
static int broker_run(int argc,char **argv){
    if(argc!=6||(strcmp(argv[1],"launch")&&strcmp(argv[1],"inspect")&&strcmp(argv[1],"quiesce")&&strcmp(argv[1],"probe"))||!identifier(argv[2])||!identifier(argv[4]))return 64;
    unsigned char uuid[16];
    if(!uuid_decode(argv[3],uuid)||!uuid_decode(argv[5],uuid)||getuid()==0)return 64;
    if(syscall(SYS_close_range,3U,~0U,0U)!=0)return 70;
    if(clearenv()!=0||setenv("LC_ALL","C",1)!=0||setenv("TZ","UTC",1)!=0)return 70;
    (void)signal(SIGPIPE,SIG_IGN);
    struct profile profile;
    memset(&profile,0,sizeof(profile));
    if(load_profile(argv[2],argv[3],argv[4],&profile)!=0)return 77;
    if((!strcmp(argv[1],"launch")||!strcmp(argv[1],"probe"))&&require_supervisor_signal_capability()!=0)return 77;
    struct realm realm;
    if(open_realm(&profile,&realm)!=0){close_realm(&realm);
        return 75;
    }
    int result=78;
    struct protocol session;
    struct frame frame;
    bool launch=strcmp(argv[1],"launch")==0;
    enum frame_type first=launch?PREPARE:strcmp(argv[1],"inspect")==0?INSPECT:strcmp(argv[1],"quiesce")==0?QUIESCE:PROBE;
    if(bootstrap_protocol(&session,STDIN_FILENO,&profile,argv[5],monotonic_ms()+((uint64_t)profile.seconds+10)*1000,first,&frame)!=0)goto done;
    if((launch&&frame.size!=192)||(first==PROBE&&frame.size!=1)||(!launch&&first!=PROBE&&frame.size<1))goto done;
    if(!launch&&expect_eof(STDIN_FILENO,session.deadline)!=0)goto done;
    unsigned int probe=first==PROBE?frame.body[0]:UINT_MAX;
    unsigned char plan[192];
    if(launch)memcpy(plan,frame.body,192);
    if((launch||first==PROBE)&&execution_capacity(realm.control)!=0)goto done;
    int execution=open_execution(&realm,argv[5],launch||first==PROBE);
    if(execution<0)goto done;
    int gate=execution_gate(execution);
    if(gate<0){close(execution);
        goto done;
    }
    struct kernel_state kernel;
    memset(&kernel,0,sizeof(kernel));
    kernel.supervisor=(uint64_t)getpid();
    if(boot_id(kernel.boot)!=0||proc_ticks(getpid(),&kernel.supervisor_ticks)!=0)goto close_execution;
    lock_hash(&realm.lock_identity,kernel.lock_hash);
    if(launch&&!stopped(execution)){
        unsigned char prepared[80];
        memcpy(prepared,kernel.boot,16);
        put64(prepared+16,kernel.supervisor);
        put64(prepared+24,kernel.supervisor_ticks);
        put64(prepared+32,(uint64_t)realm.lock_identity.st_dev);
        put64(prepared+40,(uint64_t)realm.lock_identity.st_ino);
        binding_hash(&session.binding,prepared+48);
        int prepared_group=open_group(&profile,argv[5],true,&kernel);
        if(prepared_group>=0){close(prepared_group);
            binding_hash(&session.binding,kernel.control);
            if(immutable_marker(execution,"PLAN",plan,sizeof(plan))==0&&kernel_marker_write(execution,"PREPARED_KERNEL",&kernel)==0&&binding_marker_write(execution,&session.binding)==0&&set_active(&realm,argv[5],&session,&kernel)==0&&send_frame(&session,PREPARED,prepared,sizeof(prepared))==0)result=launch_scan(&session,&profile,&realm,execution,gate,argv[5],plan,&kernel)==0?0:78;
            if(result!=0){/* No successful response is sent; durable recovery remains available after owner exit. */(void)stop_execution(execution,&kernel);
            }
        }
    }else if(first==PROBE){
        int prepared_group=open_group(&profile,argv[5],true,&kernel);
        if(prepared_group>=0){close(prepared_group);
            binding_hash(&session.binding,kernel.control);
            if(binding_marker_write(execution,&session.binding)==0&&kernel_marker_write(execution,"PREPARED_KERNEL",&kernel)==0&&set_active(&realm,argv[5],&session,&kernel)==0)result=probe_execution(&session,&profile,&realm,execution,argv[5],probe,&kernel)==0?0:78;
        }
    }else if(first==QUIESCE||first==INSPECT){
        if(recovery_descriptor(&session,execution,&frame,first==QUIESCE)==0)result=(first==QUIESCE?recover_execution(&session,&profile,execution,argv[5],&kernel,true):inspect_execution(&session,&profile,execution,argv[5],&kernel))==0?0:78;
    }
    close_execution:close(gate);
    close(execution);
    done:close_realm(&realm);
    return result;
}
#ifndef MSF_BROKER_TEST_INCLUDE
int main(int argc,char **argv){return broker_run(argc,argv);
}
#endif
