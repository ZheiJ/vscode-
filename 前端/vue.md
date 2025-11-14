## 基础

```vue
<template>
  <div>
    <input type="text" v-model="title" />
    <h1>{{ title }}</h1>
    <v-bind:img src="imgUrl" alt="后端返回" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      title: "前端笔记",
    };
  },
};
</script>
```

## 生命周期函数

1. onBeforeMount 初始化前，DOM 未就绪，不可操作真实 DOM。（页面未加载完成）
2. onMounted 初始化时，DOM 已就绪，可操作真实 DOM。

## 父子组件

调用子组件
import son from "./components/son.vue";//类似于引入其他类
export default{
//类似于调用函数方法，不过没有方法，直接调用页面
components:{
son
}
}

## 传递数据 props

props 只能父亲传给儿子，不能反过来

## 组合式 api

用 setup()表示这就是组合式 api

```vue
<template>
  <p>{{ message }}</p>
  <p>{{ user.name }}</p>
</template>
<script setup>
import { ref, reactive } from "vue";
const message = ref("组合式api");
const user = reactive({
  name: "组合式api",
});
</script>
```
