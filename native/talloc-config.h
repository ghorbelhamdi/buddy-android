/* What talloc's waf configure finds on Android (API 30+, arm64), for building talloc.c on its own (native/build.sh). */
#define HAVE_STDBOOL_H 1
#define HAVE_BOOL 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STDLIB_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_STDARG_H 1
#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_SIG_ATOMIC_T_TYPE 1
#define HAVE_ERRNO_DECL 1
#define HAVE_ENVIRON_DECL 1
#define HAVE_LIMITS_H 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_VISIBILITY_ATTR 1
#define HAVE_FUNCTION_ATTRIBUTE_FORMAT 1
#define HAVE_WORKING_STRPTIME 1
#define HAVE_SYSCONF 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0
#define HAVE_USLEEP 1
#define HAVE_DLFCN_H 1
#define HAVE_DLOPEN 1
#define HAVE_DLSYM 1
#define HAVE_DLERROR 1
#define HAVE_DLCLOSE 1
/* bionic has memset_explicit only from API 35: the same as talloc's own fallback, inline */
#define HAVE_MEMSET_EXPLICIT 1
static inline void *memset_explicit(void *b, int c, __SIZE_TYPE__ n)
{
	void *p = __builtin_memset(b, c, n);
	__asm__ volatile("" : : "r"(b) : "memory");
	return p;
}
