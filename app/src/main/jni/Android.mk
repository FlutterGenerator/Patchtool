LOCAL_PATH := $(call my-dir)

# ==================================================
# Keystone (optional)
#   headers : jni/include/keystone/keystone.h  (or Keystone/)
#   library : jni/libs/<abi>/libkeystone.a
# ==================================================

KS_HEADER := $(wildcard $(LOCAL_PATH)/include/keystone/keystone.h) \
             $(wildcard $(LOCAL_PATH)/include/Keystone/keystone.h)
KS_LIB := $(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libkeystone.a)

KS_ENABLED :=

ifneq ($(strip $(KS_HEADER)),)
ifneq ($(strip $(KS_LIB)),)
KS_ENABLED := 1
endif
endif

# ==================================================
# Capstone (optional)
#   headers : jni/include/capstone/capstone.h  (or Capstone/)
#   library : jni/libs/<abi>/libcapstone.a
# ==================================================

CS_HEADER := $(wildcard $(LOCAL_PATH)/include/capstone/capstone.h) \
             $(wildcard $(LOCAL_PATH)/include/Capstone/capstone.h)
CS_LIB := $(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libcapstone.a)

CS_ENABLED :=

ifneq ($(strip $(CS_HEADER)),)
ifneq ($(strip $(CS_LIB)),)
CS_ENABLED := 1
endif
endif

# ==================================================
# Keystone static library
# ==================================================

ifeq ($(KS_ENABLED),1)

include $(CLEAR_VARS)

LOCAL_MODULE := keystone
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libkeystone.a

include $(PREBUILT_STATIC_LIBRARY)

endif

# ==================================================
# Capstone static library
# ==================================================

ifeq ($(CS_ENABLED),1)

include $(CLEAR_VARS)

LOCAL_MODULE := capstone
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libcapstone.a

include $(PREBUILT_STATIC_LIBRARY)

endif

# ==================================================
# Main JNI library
# ==================================================

include $(CLEAR_VARS)

LOCAL_MODULE := modmenu
LOCAL_SRC_FILES := modmenu.cpp

LOCAL_CPPFLAGS := -std=c++17 -O2 -fexceptions -frtti -Wall
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include

LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384

ifeq ($(KS_ENABLED),1)
    LOCAL_CPPFLAGS += -DUSE_KEYSTONE
    LOCAL_STATIC_LIBRARIES += keystone
else
    LOCAL_CPPFLAGS += -DNO_KEYSTONE
endif

ifeq ($(CS_ENABLED),1)
    LOCAL_CPPFLAGS += -DUSE_CAPSTONE
    LOCAL_STATIC_LIBRARIES += capstone
else
    LOCAL_CPPFLAGS += -DNO_CAPSTONE
endif

include $(BUILD_SHARED_LIBRARY)
