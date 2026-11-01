
LOCAL_PATH := $(call my-dir)

# ==================================================
# Keystone (optional)
# ==================================================

KS_HEADER := $(wildcard $(LOCAL_PATH)/include/Keystone/keystone.h)
KS_LIB := $(wildcard $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libkeystone.a)

KS_ENABLED :=

ifneq ($(KS_HEADER),)
ifneq ($(KS_LIB),)
KS_ENABLED := 1
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
endif

include $(BUILD_SHARED_LIBRARY)
