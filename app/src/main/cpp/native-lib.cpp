#include <algorithm>
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <optional>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>
#include <utility>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

struct RPCSXApi {
  bool (*overlayPadData)(int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*initialize)(std::string_view rootDir, std::string_view internalDir,
                     std::string_view user);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  bool (*collectGameInfo)(JNIEnv *env, std::string_view rootDir,
                          long progressId);
  void (*shutdown)();
  int (*boot)(std::string_view path_);
  int (*getState)();
  void (*kill)();
  void (*resume)();
  void (*openHomeMenu)();
  std::string (*getTitleId)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  bool (*usbDeviceEvent)(int fd, int vendorId, int productId, int event);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*isInstallableFile)(jint fd);
  jstring (*getDirInstallPath)(JNIEnv *env, jint fd);
  bool (*install)(JNIEnv *env, int fd, long progressId);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                     std::string_view gamePath);
  std::string (*systemInfo)();
  void (*loginUser)(std::string_view userId);
  std::string (*getUser)();
  std::string (*settingsGet)(std::string_view path);
  bool (*settingsSet)(std::string_view path, std::string_view valueString);
  std::string (*getVersion)();
  std::string (*patchEngineVersion)();
  std::string (*patchesList)();
  bool (*patchSetEnabled)(std::string_view hash, std::string_view description, bool enabled);
  bool (*customConfigExists)(std::string_view serial);
  bool (*customConfigCreate)(std::string_view serial);
  bool (*customConfigDelete)(std::string_view serial);
  std::string (*customConfigGet)(std::string_view serial, std::string_view path);
  bool (*customConfigSet)(std::string_view serial, std::string_view path, std::string_view valueString);
  bool (*customConfigImport)(std::string_view serial, std::string_view yaml);
  void *(*setCustomDriver)(void *driverHandle);
  void (*setMaxCompileThreads)(int count);
  void (*setCompileMemoryBudget)(long long bytes);
  void (*setPowerSaveMode)(int on);
  void (*setThermalFrameCap)(float fps);
  int (*getRsxThreadTid)();
  long long (*getFrameWorkNanos)();
  long long (*getFramePeriodNanos)();
  void (*setCpuAffinityMode)(int on);
  void (*setWfeMode)(int on);
  void (*setSmoothShaders)(int on);
  std::string (*rpcnGetConfig)();
  void (*rpcnSetCredentials)(std::string_view npid, std::string_view password, std::string_view token);
  std::string (*rpcnGetHosts)();
  bool (*rpcnAddHost)(std::string_view description, std::string_view host);
  bool (*rpcnRemoveHost)(std::string_view host);
  void (*rpcnSetActiveHost)(std::string_view host);
  std::string (*rpcnGetActiveHost)();
  std::string (*rpcnCreateAccount)(std::string_view npid, std::string_view password, std::string_view online_name, std::string_view email, std::string_view country);
  std::string (*rpcnResendToken)();
  std::string (*rpcnTestConnection)();
  void (*rpcnSetEnabled)(int enabled);
  bool (*rpcnIsEnabled)();
};

struct RPCSXLibrary : RPCSXApi {
  void *handle = nullptr;

  RPCSXLibrary() = default;
  RPCSXLibrary(const RPCSXLibrary &) = delete;
  RPCSXLibrary(RPCSXLibrary &&other) { swap(other); }
  RPCSXLibrary &operator=(RPCSXLibrary &&other) {
    swap(other);
    return *this;
  }
  ~RPCSXLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(RPCSXLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<RPCSXApi &>(*this), static_cast<RPCSXApi &>(other));
  }

  static std::optional<RPCSXLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                          "Failed to open RPCSX library at %s, error %s", path,
                          ::dlerror());
      return {};
    }

    RPCSXLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "_rpcsx_overlayPadData"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "_rpcsx_initialize"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "_rpcsx_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "_rpcsx_startMainThreadProcessor"));
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_rpcsx_collectGameInfo"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_rpcsx_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_rpcsx_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_rpcsx_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_rpcsx_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_rpcsx_resume"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "_rpcsx_openHomeMenu"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_rpcsx_getTitleId"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_rpcsx_surfaceEvent"));
    result.usbDeviceEvent = reinterpret_cast<decltype(usbDeviceEvent)>(dlsym(handle, "_rpcsx_usbDeviceEvent"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "_rpcsx_installFw"));
    result.isInstallableFile = reinterpret_cast<decltype(isInstallableFile)>(dlsym(handle, "_rpcsx_isInstallableFile"));
    result.getDirInstallPath = reinterpret_cast<decltype(getDirInstallPath)>(dlsym(handle, "_rpcsx_getDirInstallPath"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "_rpcsx_install"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "_rpcsx_installKey"));
    result.systemInfo = reinterpret_cast<decltype(systemInfo)>(dlsym(handle, "_rpcsx_systemInfo"));
    result.loginUser = reinterpret_cast<decltype(loginUser)>(dlsym(handle, "_rpcsx_loginUser"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "_rpcsx_getUser"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "_rpcsx_settingsGet"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "_rpcsx_settingsSet"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_rpcsx_getVersion"));
    result.patchEngineVersion = reinterpret_cast<decltype(patchEngineVersion)>(dlsym(handle, "_rpcsx_patchEngineVersion"));
    result.patchesList = reinterpret_cast<decltype(patchesList)>(dlsym(handle, "_rpcsx_patchesList"));
    result.patchSetEnabled = reinterpret_cast<decltype(patchSetEnabled)>(dlsym(handle, "_rpcsx_patchSetEnabled"));
    result.customConfigExists = reinterpret_cast<decltype(customConfigExists)>(dlsym(handle, "_rpcsx_customConfigExists"));
    result.customConfigCreate = reinterpret_cast<decltype(customConfigCreate)>(dlsym(handle, "_rpcsx_customConfigCreate"));
    result.customConfigDelete = reinterpret_cast<decltype(customConfigDelete)>(dlsym(handle, "_rpcsx_customConfigDelete"));
    result.customConfigGet = reinterpret_cast<decltype(customConfigGet)>(dlsym(handle, "_rpcsx_customConfigGet"));
    result.customConfigSet = reinterpret_cast<decltype(customConfigSet)>(dlsym(handle, "_rpcsx_customConfigSet"));
    result.customConfigImport = reinterpret_cast<decltype(customConfigImport)>(dlsym(handle, "_rpcsx_customConfigImport"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_rpcsx_setCustomDriver"));
    result.setMaxCompileThreads = reinterpret_cast<decltype(setMaxCompileThreads)>(dlsym(handle, "_rpcsx_setMaxCompileThreads"));
    result.setCompileMemoryBudget = reinterpret_cast<decltype(setCompileMemoryBudget)>(dlsym(handle, "_rpcsx_setCompileMemoryBudget"));
    result.setPowerSaveMode = reinterpret_cast<decltype(setPowerSaveMode)>(dlsym(handle, "_rpcsx_setPowerSaveMode"));
    result.setThermalFrameCap = reinterpret_cast<decltype(setThermalFrameCap)>(dlsym(handle, "_rpcsx_setThermalFrameCap"));
    result.getRsxThreadTid = reinterpret_cast<decltype(getRsxThreadTid)>(dlsym(handle, "_rpcsx_getRsxThreadTid"));
    result.getFrameWorkNanos = reinterpret_cast<decltype(getFrameWorkNanos)>(dlsym(handle, "_rpcsx_getFrameWorkNanos"));
    result.getFramePeriodNanos = reinterpret_cast<decltype(getFramePeriodNanos)>(dlsym(handle, "_rpcsx_getFramePeriodNanos"));
    result.setCpuAffinityMode = reinterpret_cast<decltype(setCpuAffinityMode)>(dlsym(handle, "_rpcsx_setCpuAffinityMode"));
    result.setWfeMode = reinterpret_cast<decltype(setWfeMode)>(dlsym(handle, "_rpcsx_setWfeMode"));
    result.setSmoothShaders = reinterpret_cast<decltype(setSmoothShaders)>(dlsym(handle, "_rpcsx_setSmoothShaders"));
    result.rpcnGetConfig = reinterpret_cast<decltype(rpcnGetConfig)>(dlsym(handle, "_rpcsx_rpcnGetConfig"));
    result.rpcnSetCredentials = reinterpret_cast<decltype(rpcnSetCredentials)>(dlsym(handle, "_rpcsx_rpcnSetCredentials"));
    result.rpcnGetHosts = reinterpret_cast<decltype(rpcnGetHosts)>(dlsym(handle, "_rpcsx_rpcnGetHosts"));
    result.rpcnAddHost = reinterpret_cast<decltype(rpcnAddHost)>(dlsym(handle, "_rpcsx_rpcnAddHost"));
    result.rpcnRemoveHost = reinterpret_cast<decltype(rpcnRemoveHost)>(dlsym(handle, "_rpcsx_rpcnRemoveHost"));
    result.rpcnSetActiveHost = reinterpret_cast<decltype(rpcnSetActiveHost)>(dlsym(handle, "_rpcsx_rpcnSetActiveHost"));
    result.rpcnGetActiveHost = reinterpret_cast<decltype(rpcnGetActiveHost)>(dlsym(handle, "_rpcsx_rpcnGetActiveHost"));
    result.rpcnCreateAccount = reinterpret_cast<decltype(rpcnCreateAccount)>(dlsym(handle, "_rpcsx_rpcnCreateAccount"));
    result.rpcnResendToken = reinterpret_cast<decltype(rpcnResendToken)>(dlsym(handle, "_rpcsx_rpcnResendToken"));
    result.rpcnTestConnection = reinterpret_cast<decltype(rpcnTestConnection)>(dlsym(handle, "_rpcsx_rpcnTestConnection"));
    result.rpcnSetEnabled = reinterpret_cast<decltype(rpcnSetEnabled)>(dlsym(handle, "_rpcsx_rpcnSetEnabled"));
    result.rpcnIsEnabled = reinterpret_cast<decltype(rpcnIsEnabled)>(dlsym(handle, "_rpcsx_rpcnIsEnabled"));
    // clang-format on

    return result;
  }
};

static RPCSXLibrary rpcsxLib;

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    rpcsxLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getLibraryVersion(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    if (auto getVersion = library->getVersion) {
      return wrap(env, getVersion());
    }
  }

  return {};
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_overlayPadData(
    JNIEnv *, jobject, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  return rpcsxLib.overlayPadData(digital1, digital2, leftStickX, leftStickY,
                                 rightStickX, rightStickY);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring internalDir, jstring user) {
  return rpcsxLib.initialize(unwrap(env, rootDir), unwrap(env, internalDir),
                             unwrap(env, user));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_processCompilationQueue(JNIEnv *env, jobject) {
  return rpcsxLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_startMainThreadProcessor(JNIEnv *env, jobject) {
  return rpcsxLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_collectGameInfo(
    JNIEnv *env, jobject, jstring jrootDir, jlong progressId) {
  return rpcsxLib.collectGameInfo(env, unwrap(env, jrootDir), progressId);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_shutdown(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_boot(JNIEnv *env,
                                                            jobject,
                                                            jstring jpath) {
  return rpcsxLib.boot(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_getState(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_kill(JNIEnv *env,
                                                            jobject) {
  return rpcsxLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_resume(JNIEnv *env,
                                                              jobject) {
  return rpcsxLib.resume();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_openHomeMenu(JNIEnv *env,
                                                                    jobject) {
  return rpcsxLib.openHomeMenu();
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getTitleId(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getTitleId());
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  return rpcsxLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_usbDeviceEvent(
    JNIEnv *env, jobject, jint fd, jint vendorId, jint productId, jint event) {
  return rpcsxLib.usbDeviceEvent(fd, vendorId, productId, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_isInstallableFile(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.isInstallableFile(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getDirInstallPath(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.getDirInstallPath(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_install(JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.install(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  return rpcsxLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_systemInfo(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.systemInfo());
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_loginUser(JNIEnv *env, jobject, jstring user_id) {
  return rpcsxLib.loginUser(unwrap(env, user_id));
}

extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_getUser(JNIEnv *env,
                                                                  jobject) {
  return wrap(env, rpcsxLib.getUser());
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_settingsGet(JNIEnv *env, jobject, jstring jpath) {
  return wrap(env, rpcsxLib.settingsGet(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_settingsSet(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  return rpcsxLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setMaxCompileThreads(
    JNIEnv *, jobject, jint count) {
  // Guard: an older core library may not export this symbol.
  if (!rpcsxLib.setMaxCompileThreads) return;
  rpcsxLib.setMaxCompileThreads(count);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setCompileMemoryBudget(
    JNIEnv *, jobject, jlong bytes) {
  // Guard: an older core library may not export this symbol.
  if (!rpcsxLib.setCompileMemoryBudget) return;
  rpcsxLib.setCompileMemoryBudget(static_cast<long long>(bytes));
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setPowerSaveMode(
    JNIEnv *, jobject, jboolean on) {
  if (!rpcsxLib.setPowerSaveMode) return;
  rpcsxLib.setPowerSaveMode(on ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setThermalFrameCap(
    JNIEnv *, jobject, jfloat fps) {
  if (!rpcsxLib.setThermalFrameCap) return;
  rpcsxLib.setThermalFrameCap(fps);
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_getRsxThreadTid(
    JNIEnv *, jobject) {
  if (!rpcsxLib.getRsxThreadTid) return 0;
  return rpcsxLib.getRsxThreadTid();
}

extern "C" JNIEXPORT jlong JNICALL Java_net_rpcsx_RPCSX_getFrameWorkNanos(
    JNIEnv *, jobject) {
  if (!rpcsxLib.getFrameWorkNanos) return 0;
  return rpcsxLib.getFrameWorkNanos();
}

extern "C" JNIEXPORT jlong JNICALL Java_net_rpcsx_RPCSX_getFramePeriodNanos(
    JNIEnv *, jobject) {
  if (!rpcsxLib.getFramePeriodNanos) return 0;
  return rpcsxLib.getFramePeriodNanos();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setCpuAffinityMode(
    JNIEnv *, jobject, jboolean on) {
  if (!rpcsxLib.setCpuAffinityMode) return;
  rpcsxLib.setCpuAffinityMode(on ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setWfeMode(
    JNIEnv *, jobject, jboolean on) {
  if (!rpcsxLib.setWfeMode) return;
  rpcsxLib.setWfeMode(on ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_setSmoothShaders(
    JNIEnv *, jobject, jboolean on) {
  if (!rpcsxLib.setSmoothShaders) return;
  rpcsxLib.setSmoothShaders(on ? 1 : 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_rpcnGetConfig(JNIEnv *env, jobject) {
  if (!rpcsxLib.rpcnGetConfig) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.rpcnGetConfig());
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_rpcnSetCredentials(
    JNIEnv *env, jobject, jstring npid, jstring password, jstring token) {
  if (!rpcsxLib.rpcnSetCredentials) return;
  rpcsxLib.rpcnSetCredentials(unwrap(env, npid), unwrap(env, password), unwrap(env, token));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_rpcnGetHosts(JNIEnv *env, jobject) {
  if (!rpcsxLib.rpcnGetHosts) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.rpcnGetHosts());
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_rpcnAddHost(
    JNIEnv *env, jobject, jstring description, jstring host) {
  if (!rpcsxLib.rpcnAddHost) return false;
  return rpcsxLib.rpcnAddHost(unwrap(env, description), unwrap(env, host));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_rpcnRemoveHost(JNIEnv *env, jobject, jstring host) {
  if (!rpcsxLib.rpcnRemoveHost) return false;
  return rpcsxLib.rpcnRemoveHost(unwrap(env, host));
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_rpcnSetActiveHost(JNIEnv *env, jobject, jstring host) {
  if (!rpcsxLib.rpcnSetActiveHost) return;
  rpcsxLib.rpcnSetActiveHost(unwrap(env, host));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_rpcnGetActiveHost(JNIEnv *env, jobject) {
  if (!rpcsxLib.rpcnGetActiveHost) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.rpcnGetActiveHost());
}

extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_rpcnCreateAccount(
    JNIEnv *env, jobject, jstring npid, jstring password, jstring onlineName,
    jstring email, jstring country) {
  if (!rpcsxLib.rpcnCreateAccount) return wrap(env, std::string{"RPCN unavailable in this build"});
  return wrap(env, rpcsxLib.rpcnCreateAccount(unwrap(env, npid), unwrap(env, password),
                                              unwrap(env, onlineName), unwrap(env, email),
                                              unwrap(env, country)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_rpcnResendToken(JNIEnv *env, jobject) {
  if (!rpcsxLib.rpcnResendToken) return wrap(env, std::string{"RPCN unavailable in this build"});
  return wrap(env, rpcsxLib.rpcnResendToken());
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_rpcnTestConnection(JNIEnv *env, jobject) {
  if (!rpcsxLib.rpcnTestConnection) return wrap(env, std::string{"RPCN unavailable in this build"});
  return wrap(env, rpcsxLib.rpcnTestConnection());
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_rpcnSetEnabled(JNIEnv *, jobject, jboolean enabled) {
  if (!rpcsxLib.rpcnSetEnabled) return;
  rpcsxLib.rpcnSetEnabled(enabled ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_rpcnIsEnabled(JNIEnv *, jobject) {
  if (!rpcsxLib.rpcnIsEnabled) return false;
  return rpcsxLib.rpcnIsEnabled();
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_patchEngineVersion(JNIEnv *env, jobject) {
  if (!rpcsxLib.patchEngineVersion) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.patchEngineVersion());
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_patchesList(JNIEnv *env, jobject) {
  if (!rpcsxLib.patchesList) return wrap(env, std::string{"[]"});
  return wrap(env, rpcsxLib.patchesList());
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_patchSetEnabled(
    JNIEnv *env, jobject, jstring jhash, jstring jdescription,
    jboolean jenabled) {
  if (!rpcsxLib.patchSetEnabled) return false;
  return rpcsxLib.patchSetEnabled(unwrap(env, jhash), unwrap(env, jdescription),
                                  jenabled);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_customConfigExists(
    JNIEnv *env, jobject, jstring jserial) {
  if (!rpcsxLib.customConfigExists) return false;
  return rpcsxLib.customConfigExists(unwrap(env, jserial));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_customConfigCreate(
    JNIEnv *env, jobject, jstring jserial) {
  if (!rpcsxLib.customConfigCreate) return false;
  return rpcsxLib.customConfigCreate(unwrap(env, jserial));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_customConfigDelete(
    JNIEnv *env, jobject, jstring jserial) {
  if (!rpcsxLib.customConfigDelete) return false;
  return rpcsxLib.customConfigDelete(unwrap(env, jserial));
}

extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_customConfigGet(
    JNIEnv *env, jobject, jstring jserial, jstring jpath) {
  if (!rpcsxLib.customConfigGet) return wrap(env, std::string{});
  return wrap(env, rpcsxLib.customConfigGet(unwrap(env, jserial),
                                            unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_customConfigSet(
    JNIEnv *env, jobject, jstring jserial, jstring jpath, jstring jvalue) {
  if (!rpcsxLib.customConfigSet) return false;
  return rpcsxLib.customConfigSet(unwrap(env, jserial), unwrap(env, jpath),
                                  unwrap(env, jvalue));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_customConfigImport(
    JNIEnv *env, jobject, jstring jserial, jstring jyaml) {
  if (!rpcsxLib.customConfigImport) return false;
  return rpcsxLib.customConfigImport(unwrap(env, jserial), unwrap(env, jyaml));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_supportsCustomDriverLoading(JNIEnv *env,
                                                 jobject instance) {
  return access("/dev/kgsl-3d0", F_OK) == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getVersion(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getVersion());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setCustomDriver(JNIEnv *env, jobject, jstring jpath,
                                     jstring jlibraryName, jstring jhookDir) {
#ifdef __aarch64__
  if (rpcsxLib.setCustomDriver == nullptr) {
    return false;
  }

  auto path = unwrap(env, jpath);
  void *loader = nullptr;

  if (!path.empty()) {
      auto hookDir = unwrap(env, jhookDir);
      auto libraryName = unwrap(env, jlibraryName);
      __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI", "Loading custom driver %s",
                          path.c_str());

      ::dlerror();
      loader = adrenotools_open_libvulkan(
              RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, (hookDir + "/").c_str(),
              (path + "/").c_str(), libraryName.c_str(), nullptr, nullptr);

      if (loader == nullptr) {
          __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI",
                              "Failed to load custom driver at '%s': %s",
                              path.c_str(), ::dlerror());
          return false;
      }
  }

  auto prevLoader = rpcsxLib.setCustomDriver(loader);
  if (prevLoader != nullptr) {
    ::dlclose(prevLoader);
  }

  return true;
#else
  return false;
#endif // __aarch64__
}
