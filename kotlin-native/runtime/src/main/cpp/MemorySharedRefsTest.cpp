/*
 * Copyright 2010-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "MemorySharedRefs.hpp"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "Mutex.hpp"
#include "ObjectTestSupport.hpp"
#include "TestSupport.hpp"

using namespace kotlin;

namespace {

struct Payload {
    ObjHeader* field1;
    ObjHeader* field2;
    ObjHeader* field3;

    static constexpr std::array kFields = {
            &Payload::field1,
            &Payload::field2,
            &Payload::field3,
    };
};

test_support::TypeInfoHolder typeHolder{test_support::TypeInfoHolder::ObjectBuilder<Payload>()};

std_support::unique_ptr<BackRefFromAssociatedObject> AllocateObject() noexcept {
    CalledFromNativeGuard guard;
    ObjHolder holder;
    ObjHeader* obj = AllocInstance(typeHolder.typeInfo(), holder.slot());
    auto backRef = std_support::make_unique<BackRefFromAssociatedObject>();
    backRef->initAndAddRef(obj);
    return backRef;
}

}

TEST(MemorySharedRefsTest, BackRefFromAssociatedObject_addReleaseRace) {
    constexpr int repeatCount = 1000;
    BackRefFromAssociatedObject* shared = nullptr;
    std::mutex initMutex;
    std::condition_variable initCond;
    std::atomic_flag run = ATOMIC_FLAG_INIT;
    std::mutex deinitMutex;
    std::condition_variable deinitCond;

    ScopedThread t1([&] {
        for (int i = 0; i < repeatCount; ++i) {
            konan::consolePrintf("t1 i=%d\n", i);
            std::unique_lock initGuard{initMutex};
            auto ref = AllocateObject();
            shared = ref.get();
            initGuard.unlock();
            initCond.notify_all();
            while (!run.test()) {}
            ref->releaseRef();
            std::unique_lock deinitGuard{deinitMutex};
            shared = nullptr;
            deinitGuard.unlock();
            deinitCond.notify_all();
            while (run.test()) {}
        }
    });

    ScopedThread t2([&] {
        for (int i = 0; i < repeatCount; ++i) {
            konan::consolePrintf("t2 i=%d\n", i);
            std::unique_lock initGuard{initMutex};
            initCond.wait(initGuard, [&] { return shared != nullptr; });
            auto ref = shared;
            initGuard.unlock();
            run.test_and_set();
            ref->addRef<ErrorPolicy::kIgnore>();
            std::unique_lock deinitGuard{deinitMutex};
            deinitCond.wait(deinitGuard, [&] { return shared == nullptr; });
            deinitGuard.unlock();
            run.clear();
        }
    });
}
