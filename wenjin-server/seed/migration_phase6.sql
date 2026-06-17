USE `wenjin`;

-- ── 1) 加 confidence 列（幂等：列已存在则忽略报错，可手动跳过）──
ALTER TABLE `kg_edge`  ADD COLUMN `confidence` TINYINT DEFAULT NULL COMMENT 'AI 置信度 0–100，NULL=既有生效边【阶段六补充】' AFTER `relation_note`;
ALTER TABLE `question` ADD COLUMN `confidence` TINYINT DEFAULT NULL COMMENT 'AI 置信度 0–100，NULL=既有生效题【阶段六补充】' AFTER `status`;

-- ── 1.5) 给现有数据设置默认 confidence（避免排序 NPE）──
UPDATE `kg_edge` SET `confidence`=85 WHERE `course_id`=1 AND `confidence` IS NULL AND `relation_note` NOT LIKE '%待复核%';
UPDATE `question` SET `confidence`=80 WHERE `course_id`=1 AND `confidence` IS NULL;

-- ── 2) 待复核候选边（relation_note 以『待复核』开头，带 confidence）──
-- 先按特征清掉旧的待复核边，保证可重复执行
DELETE FROM `kg_edge` WHERE `course_id`=1 AND `relation_note` LIKE '%待复核%';

-- 工具：用 node_code 取本课程节点 id
-- 以下每条边 from/to 用 (SELECT id FROM kg_node WHERE course_id=1 AND node_code='KTxx')
INSERT INTO `kg_edge` (`course_id`,`from_node_id`,`to_node_id`,`relation_type`,`relation_note`,`confidence`)
SELECT 1, f.id, t.id, x.rtype, x.note, x.conf FROM (
  SELECT 'KT05' fc,'KT10' tc,1 rtype,'『待复核』：领域类的候选实体多来自业务流程中的名词与数据对象，教材 6.2 节有直接对应。' note,93 conf UNION ALL
  SELECT 'KT04','KT07',1,'『待复核』：功能需求的表述方式直接决定用例的粒度与系统边界划定。',89 UNION ALL
  SELECT 'KT13','KT18',1,'『待复核』：面向对象设计原则建立在抽象、封装等通用设计概念之上。',86 UNION ALL
  SELECT 'KT18-2','KT19',1,'『待复核』：设计类的协作结构大量复用典型模式，先识模式再构建类。',81 UNION ALL
  SELECT 'KT17','KT20',1,'『待复核』：持久化字段与映射约束影响类到代码的转换细节。',74 UNION ALL
  SELECT 'KT22','KT24',1,'『待复核』：上线前的回归与验收测试是部署的准入条件，证据来自大纲第 12 章。',69 UNION ALL
  SELECT 'KT15-1','KT17',1,'『待复核』：数据访问层的职责划分依赖分层架构的依赖规则。',66 UNION ALL
  SELECT 'KT03','KT06',3,'『待复核』：敏捷过程与团队组织方式互相影响，证据强度一般。',58 UNION ALL
  SELECT 'KT29','KT05',3,'『待复核』：原型工具可辅助业务流程梳理，仅在案例脚注中出现，证据较弱。',51
) x
JOIN `kg_node` f ON f.course_id=1 AND f.node_code=x.fc
JOIN `kg_node` t ON t.course_id=1 AND t.node_code=x.tc;

-- ── 3) 待审核题（status=0 PENDING，source=2 AI，带 confidence）+ 选项 + 主考点 ──
-- 先清掉本批种子题（用 stem 前缀标识可重复执行）
DELETE qo FROM `question_option` qo
  JOIN `question` q ON q.id=qo.question_id
  WHERE q.course_id=1 AND q.stem LIKE '[P6]%';
DELETE qn FROM `question_node` qn
  JOIN `question` q ON q.id=qn.question_id
  WHERE q.course_id=1 AND q.stem LIKE '[P6]%';
DELETE FROM `question` WHERE course_id=1 AND stem LIKE '[P6]%';

-- 用存储过程式逐题插入太繁；改用临时表 + 程序化。为简洁，逐题手写（12 题）。
-- 模板：每题插 question，取 @qid=LAST_INSERT_ID()，插 4 选项 + 1 主考点。
-- 题 1（KT10，conf95）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 网上书店「读者可将图书加入购物车」最适合提取为候选业务实体的名词组是：',1,2,'A',2,0,95);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','读者、图书、购物车',1,NULL),
 (@qid,'B','加入、购物车',0,'KT10'),
 (@qid,'C','需求、系统、功能',0,'KT10'),
 (@qid,'D','读者、操作、流程',0,'KT10');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT10';

-- 题 2（KT10，conf92）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 「一个订单包含多个订单项，订单项不能脱离订单单独存在」，正确建模是：',1,3,'A',2,0,92);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','Order 与 OrderItem 的组合关系',1,NULL),
 (@qid,'B','Order 与 OrderItem 的聚合关系',0,'KT10'),
 (@qid,'C','OrderItem 继承 Order',0,'KT10'),
 (@qid,'D','两者之间的依赖关系',0,'KT10');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT10';

-- 题 3（KT07，conf88）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 编写用例描述时，「会员卡余额不足导致支付失败」应写入：',1,2,'A',2,0,88);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','备选事件流',1,NULL),(@qid,'B','基本事件流',0,'KT07'),
 (@qid,'C','前置条件',0,'KT07'),(@qid,'D','用例目标',0,'KT07');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT07';

-- 题 4（KT07，conf86）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 「打印回执」只在勾选发票时执行，与「支付订单」的关系是：',1,3,'A',2,0,86);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','«extend» 扩展关系',1,NULL),(@qid,'B','«include» 包含关系',0,'KT07'),
 (@qid,'C','泛化关系',0,'KT07'),(@qid,'D','关联关系',0,'KT07');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT07';

-- 题 5（KT15，conf79）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 三层架构中，业务逻辑层直接读写数据库表，违反了哪条原则？',1,3,'A',2,0,79);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','各层只依赖其直接下层的接口',1,NULL),(@qid,'B','上层不得调用下层',0,'KT15'),
 (@qid,'C','层间必须异步通信',0,'KT15'),(@qid,'D','每层必须独立部署',0,'KT15');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT15';

-- 题 6（KT18，conf76）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 「新增折扣方式时不改既有结算代码而是扩展实现」体现了哪条原则？',1,3,'A',2,0,76);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','开闭原则',1,NULL),(@qid,'B','单一职责原则',0,'KT18'),
 (@qid,'C','接口隔离原则',0,'KT18'),(@qid,'D','迪米特法则',0,'KT18');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT18';

-- 题 7（KT23，conf71）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 对「1–100 的整数」做边界值分析，优先选取的测试输入是：',1,2,'A',2,0,71);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','0、1、100、101',1,NULL),(@qid,'B','50、51、52',0,'KT23'),
 (@qid,'C','-100、200、300',0,'KT23'),(@qid,'D','任意随机整数',0,'KT23');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT23';

-- 题 8（KT22，conf64）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 下列关于回归测试的说法，正确的是：',1,2,'A',2,0,64);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','修改代码后重跑既有用例，确认未引入新缺陷',1,NULL),(@qid,'B','只在首次集成时执行',0,'KT22'),
 (@qid,'C','由最终用户在生产环境执行',0,'KT22'),(@qid,'D','与单元测试完全相同',0,'KT22');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT22';

-- 题 9（KT04，conf58）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 「软件需求就是用户提出的全部想法，应全部实现」，这种说法：',1,1,'A',2,0,58);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','错误',1,NULL),(@qid,'B','正确',0,'KT04'),
 (@qid,'C','部分正确',0,'KT04'),(@qid,'D','无法判断',0,'KT04');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT04';

-- 题 10（KT02，conf91）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 瀑布模型中，需求变更代价最高的阶段是：',1,2,'A',2,0,91);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','运维阶段',1,NULL),(@qid,'B','需求分析阶段',0,'KT02'),
 (@qid,'C','设计阶段',0,'KT02'),(@qid,'D','编码阶段',0,'KT02');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT02';

-- 题 11（KT03，conf83）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 敏捷开发中「每两周交付一个可运行版本」体现的是：',1,2,'A',2,0,83);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','迭代式增量交付',1,NULL),(@qid,'B','大爆炸式集成',0,'KT03'),
 (@qid,'C','阶段化评审',0,'KT03'),(@qid,'D','文档驱动开发',0,'KT03');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT03';

-- 题 12（KT05，conf72）
INSERT INTO `question`(course_id,stem,type,difficulty,answer,source,status,confidence)
VALUES (1,'[P6] 业务流程分析的主要产出物是：',1,2,'A',2,0,72);
SET @qid=LAST_INSERT_ID();
INSERT INTO `question_option`(question_id,option_key,option_text,is_correct,point_node_code) VALUES
 (@qid,'A','业务流程图与活动图',1,NULL),(@qid,'B','类图',0,'KT05'),
 (@qid,'C','部署图',0,'KT05'),(@qid,'D','状态图',0,'KT05');
INSERT INTO `question_node`(question_id,node_id,weight)
 SELECT @qid,id,1 FROM kg_node WHERE course_id=1 AND node_code='KT05';

-- ── 4) 3 个不同水平学生 + 掌握度（避开已脏的 student2，用 id 11/12/13）──
DELETE FROM `student_mastery` WHERE course_id=1 AND student_id IN (11,12,13);
DELETE FROM `sys_user` WHERE id IN (11,12,13);
INSERT INTO `sys_user`(id,username,password,real_name,role,status) VALUES
 (11,'p6_stu_a','demo','学优生·甲',2,1),
 (12,'p6_stu_b','demo','中等生·乙',2,1),
 (13,'p6_stu_c','demo','学困生·丙',2,1);

-- 对一组已开课节点写不同水平（level：2=已掌握 score≥75 / 1=薄弱 40–74 / 0=未学 <40）
-- 甲（强）：多数已掌握；乙（中）：半数薄弱；丙（弱）：多数薄弱/未学
INSERT INTO `student_mastery`(student_id,course_id,node_id,mastery_score,mastery_level)
SELECT s.sid, 1, n.id, s.score, s.lvl FROM (
  -- 甲 11
  SELECT 11 sid,'KT01' nc,88 score,2 lvl UNION ALL SELECT 11,'KT02',85,2 UNION ALL
  SELECT 11,'KT04',82,2 UNION ALL SELECT 11,'KT05',80,2 UNION ALL
  SELECT 11,'KT07',78,2 UNION ALL SELECT 11,'KT10',76,2 UNION ALL
  SELECT 11,'KT13',70,1 UNION ALL SELECT 11,'KT15',66,1 UNION ALL
  SELECT 11,'KT18',72,1 UNION ALL SELECT 11,'KT20',55,1 UNION ALL
  -- 乙 12
  SELECT 12,'KT01',80,2 UNION ALL SELECT 12,'KT02',74,1 UNION ALL
  SELECT 12,'KT04',68,1 UNION ALL SELECT 12,'KT05',62,1 UNION ALL
  SELECT 12,'KT07',55,1 UNION ALL SELECT 12,'KT10',50,1 UNION ALL
  SELECT 12,'KT13',58,1 UNION ALL SELECT 12,'KT15',45,1 UNION ALL
  SELECT 12,'KT18',48,1 UNION ALL SELECT 12,'KT20',35,0 UNION ALL
  -- 丙 13
  SELECT 13,'KT01',60,1 UNION ALL SELECT 13,'KT02',52,1 UNION ALL
  SELECT 13,'KT04',44,1 UNION ALL SELECT 13,'KT05',40,1 UNION ALL
  SELECT 13,'KT07',32,0 UNION ALL SELECT 13,'KT10',28,0 UNION ALL
  SELECT 13,'KT13',38,0 UNION ALL SELECT 13,'KT15',25,0 UNION ALL
  SELECT 13,'KT18',30,0 UNION ALL SELECT 13,'KT20',20,0
) s
JOIN `kg_node` n ON n.course_id=1 AND n.node_code=s.nc;
