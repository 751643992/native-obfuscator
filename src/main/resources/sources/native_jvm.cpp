#include "native_jvm.hpp"

namespace native_jvm::utils {

	union __fi_conv { 
		jfloat m_jfloat; 
		jint m_jint; 
	};

	jint cfi(jfloat f) { 
		__fi_conv fi; 
		fi.m_jfloat = f;
		return fi.m_jint; 
	}

	jfloat cif(jint i) { 
		__fi_conv fi; 
		fi.m_jint = i; 
		return fi.m_jfloat; 
	}


	union __dl_conv { 
		jdouble m_jdouble; 
		jlong m_jlong; 
	};

	jlong cdl(jdouble d) { 
		__dl_conv dl; 
		dl.m_jdouble = d; 
		return dl.m_jlong;
	}

	jdouble cld(jlong l) { 
		__dl_conv dl; 
		dl.m_jlong = l; 
		return dl.m_jdouble; 
	}


	jobjectArray create_multidim_array(JNIEnv *env, jint count, jint *sizes, std::string clazz) {
		if (count == 0)
			return (jobjectArray) nullptr;
		jobjectArray resultArray = env->NewObjectArray(*sizes, env->FindClass((std::string(count, '[') + clazz).c_str()), nullptr);
		for (jint i = 0; i < *sizes; i++)
			env->SetObjectArrayElement(resultArray, i, create_multidim_array(env, count - 1, sizes + 1, clazz));
		return resultArray;
	}
}