# python

## 基础

### 循环

whlie

```python
n=8
while n < 10:
    n++
```

for
for 临时变量 in 数据容器
从容器中依次取出元素赋值给临时变量

```python
a="aiowujd"
mylist={2,"呆"}
for x in mylist:
for x in a:
    print(x)

```

### 组合显示

三种：

```python
name="諈"
n=99
print(f"你好{name}{n}")

print("你好%s"%name)


```

### 容器

#### list 列表

类似于数组，但是没有数据类型，写法：

```python
    # 创建空列表
    L=list()
    mylist = ['你好',8,True,[8,"zheizhei"]]
    print(mylist)
    # 输出为['你好'，8，true,[8,"zheizhei"]]
    print(mylist[3][1])
    # 输出为zheizhei
```

##### 函数

- index 查询函数
  ```python
  index = mylist.index("你好")
  print("你好的下标为%d"%index)
  ```
  index 为 mylist 的脚标
- insert 插入
  ```python
  mylist.insert(2,8)
  print("插入8后")
  print(mylist)
  ```
  第一个元素是下标，第二个元素是插入的元素
- append 插入尾部
  ```python
  mylist.append("黄俊杰")
  print("插入后")
  ```
- extend 插入一整个列表（末尾）
  用法同上
- 删除

1. del mylist[1]
2. mylist.pop[0]
3. remove
4. clear ！清空

```python
 del mylist[1]
 print(f"删除下标为1的元素后：{mylist}")

 element=mylist.pop[0]
 print(f"删除的元素是{element}")

 mylist.remove(True) # 注意不要有""
 print(f"删除True后{mylist}")
 mylist.clear()#
```

- count 计算某元素出现次数
  print(mylist.count(8))
- len 计算长度

#### tuple 元组

- 类似于列表，但是创建后不可被修改
- 方法函数相同与列表
  - index 查找
  - count 统计
  - len 长度

```python
t=(21,"你好")
# 当只有一个元素时,必须加逗号，不然不是元组
t=(2,)
```

#### string 字符串

- 不可修改
- 可以用下标
- 函数

  - replace 替换
  - split 分割字符串(变成列表 list)
  - strip 字符串规整操作
  - count 统计
  - len

  <br />示例：

  ```python
  str = "阿瓦达 dwioja oi hh i"
  new_str = str.replace("i","你哈后")
  print(new_str)

  # split
  new_str2 = str.split(" ")# 根据空格切分
  print(f"切割{str}后为{new_str2},数据类型为{type(new_str2)}")

  # strip
  # 1.()里不填参数则是去除回车，开头及结尾空格
  my_str1="  aidwo  awd  awd  "
  print(f"规整前{my_str1}")
  print(f"规整后{my_str1.strip()}")
  # 2.（）里填参数，除去指定字符,只能除去前后的，并且不能有空格
  print(f"去除指定字符后：{my_str1.strip('ad')}")
  ```
