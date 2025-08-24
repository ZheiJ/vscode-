#c语言的鼠标和键盘交互
##一. 准备工作
- graphics.h的头文件或者用easy.x也可以
- initgraph创建窗口
- while让窗口保持开启
- 最后应有closegraph和关闭窗口
##二. 鼠标的交互
1. 定义鼠标消息
    ExMessage m;<font color="red">存储鼠标信息</font>
    int a; &emsp;&emsp;&emsp; <font color="red">整数a</font>
2. 获取鼠标消息
peekmessage(&m,EM_MOUSE);
3. 分析鼠标消息
m.message:<font color="red">区分鼠标消息类型</font>
m.x;    <font color="red">鼠标当前位置x的坐标</font>
m.y;    <font color="red">鼠标当前位置y的坐标</font>
- 示例：
```c
main(){
    peekmessage(&m,EM_MOUSE);//获取的鼠标的操作
    //点击左键
    if(m.Message==WM_LIFTBOTTONDOWN){
        circle(m.x,m.y,10);//画圆
    }
    //移动鼠标
    if(m.Message==WM_MOUSEMOVE){

    }
}
```
