package org.whystudio.internship.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mchange.lang.IntegerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.whystudio.internship.controller.ControllerUtil;
import org.whystudio.internship.entity.*;
import org.whystudio.internship.mapper.StudentMapper;
import org.whystudio.internship.mapper.TeacherMapper;
import org.whystudio.internship.service.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.whystudio.internship.util.JWTTool;
import org.whystudio.internship.vo.Const;
import org.whystudio.internship.vo.JsonResult;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 校内导师信息 服务实现类
 * </p>
 *
 * @author mrruan
 * @since 2020-03-05
 */
@Service
public class TeacherServiceImpl extends ServiceImpl<TeacherMapper, Teacher> implements ITeacherService {

    @Autowired
    TeacherMapper teacherMapper;

    @Autowired
    StudentMapper studentMapper;

    @Autowired
    IStudentService studentService;

    @Autowired
    IReportdateService reportdateService;

    @Autowired
    IReportService reportService;

    @Autowired
    IAppraisalService appraisalService;

    @Autowired
    IAppraisaldateService appraisaldateService;

    @Override
    public JsonResult getPersonalInfo(String token) {
        //鉴权成功的话, 这里不需要在判断token为空了, 肯定不为空
        String teachNo = JWTTool.findToken(token);
        Teacher teacher = teacherMapper.selectByTeachno(teachNo); // unique teachNo
        if (teacher == null) {
            return ControllerUtil.getFalseResultMsgBySelf("没有找到对应教师信息");
        } else if (!teacher.getStatus()) {
            return ControllerUtil.getFalseResultMsgBySelf("该教师已被锁定");
        } else {
            // teacher.setPassword(null); //添加了@JsonIgnore
            return ControllerUtil.getSuccessResultBySelf(teacher);
        }
    }

    @Override
    public JsonResult updatePersonalInfo(String token, Teacher teacher) {
        String teachNo = JWTTool.findToken(token); // not null
        Teacher teacher1 = teacherMapper.selectByTeachno(teachNo);
        teacher1.setAge((teacher.getAge() == null) ? teacher1.getAge() : teacher.getAge());
        teacher1.setSex(StringUtils.isBlank(teacher.getSex()) ? teacher1.getSex() : teacher.getSex());
        LambdaUpdateWrapper<Teacher> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Teacher::getTeachno, teachNo);
        if (teacherMapper.update(teacher1, lambdaUpdateWrapper) > 0) {
            return ControllerUtil.getDataResult(teacher1);
        } else {
            return ControllerUtil.getFalseResultMsgBySelf("更新信息失败");
        }
    }

    @Override
    public JsonResult getMyStudents(String token) {
        String teachNo = JWTTool.findToken(token); // not null
        List<Map<String, Object>> students = studentMapper.listTeachersStudentWithReportStatusAndAppraisalStatus(teachNo);
        // 已经对Student密码区域增加了@JsonIgnore
        return ControllerUtil.getDataResult(students);
    }

    @Override
    @Transactional
    public JsonResult myStudentReport(String token, String stuno) {
        String teachNo = JWTTool.findToken(token); // teachNo不为空
        Student student = studentService.lambdaQuery().eq(Student::getStuno, stuno).one();
        if (null == student || !student.getTeachno().equals(teachNo)) {
            return ControllerUtil.getFalseResultMsgBySelf("无权访问该学生信息");
        }
        //找report
        Report report = reportService.lambdaQuery().eq(Report::getStuno, student.getStuno()).one();
        Reportdate reportdate = reportdateService.lambdaQuery().eq(Reportdate::getStuno, stuno).one();
        if (report == null) {
            report = new Report();
            reportdate = new Reportdate();
            report.setStuno(student.getStuno());
            reportdate.setStuno(student.getStuno());
            if (!(reportService.save(report) && reportdateService.save(reportdate))) {
                return ControllerUtil.getFalseResultMsgBySelf("没有获取到报告册信息, 无法保存到报告册信息");
            }
        }
        Map<String, Object> rs = new HashMap<>();
        rs.put("report", report);
        rs.put("reportdate", reportdate);
        rs.put("student", student);
        return ControllerUtil.getDataResult(rs);

    }

    @Override
    @Transactional
    public JsonResult myStudentAppraisal(String token, String stuno) {
        String teachNo = JWTTool.findToken(token); // teachNo不为空
        Student student = studentService.lambdaQuery().eq(Student::getStuno, stuno).one();
        if (null == student || !student.getTeachno().equals(teachNo)) {
            return ControllerUtil.getFalseResultMsgBySelf("你无权访问该学生信息");
        }
        Appraisal appraisal = appraisalService.lambdaQuery().eq(Appraisal::getStuno, stuno).one();
        Appraisaldate appraisaldate = appraisaldateService.lambdaQuery().eq(Appraisaldate::getStuno, stuno).one();
        if (appraisal == null) {
            appraisal = new Appraisal();
            appraisaldate = new Appraisaldate();
            appraisal.setStuno(student.getStuno());
            appraisaldate.setStuno(student.getStuno());
            if (!(appraisalService.save(appraisal) && appraisaldateService.save(appraisaldate))) {
                return ControllerUtil.getFalseResultMsgBySelf("没有获取到鉴定表信息, 无法保存到新的鉴定表信息");
            }
        }
        Map<String, Object> rs = new HashMap<>();
        rs.put("appraisal", appraisal);
        rs.put("appraisaldate", appraisaldate);
        rs.put("student", student);
        return ControllerUtil.getDataResult(rs);

    }

    @Override
    @Transactional
    public JsonResult evalStudentAppraisal(String token, String stuno, String corpTeacherGrade, String teacherGrade, String leaderOpinion) {
        String[] grades = {Const.NO_PASS, Const.PASS, Const.USUAL, Const.GOOD, Const.PERFECT};
        List<String> list = Arrays.asList(grades);
        if (!StringUtils.isBlank(corpTeacherGrade) && !list.contains(corpTeacherGrade)) {
            corpTeacherGrade = Const.NO_PASS;
        }
        if (!StringUtils.isBlank(teacherGrade) && !list.contains(teacherGrade)) {
            teacherGrade = Const.NO_PASS;
        }
        String teachNo = JWTTool.findToken(token); // teachNo不为空
        Student student = studentService.lambdaQuery().eq(Student::getStuno, stuno).one();
        if (null == student || !student.getTeachno().equals(teachNo)) {
            return ControllerUtil.getFalseResultMsgBySelf("无权访问该学生信息");
        }
        Appraisal appraisal = appraisalService.lambdaQuery().eq(Appraisal::getStuno, stuno).one();
        if (appraisal == null) {
            return ControllerUtil.getFalseResultMsgBySelf("无法评价空的鉴定表");
        }
        appraisal.setCorpTeacherGrade((corpTeacherGrade == null) ? appraisal.getCorpTeacherGrade() : corpTeacherGrade);
        appraisal.setTeacherGrade((teacherGrade == null) ? appraisal.getTeacherGrade() : teacherGrade);
        appraisal.setLeaderOpinion((leaderOpinion == null) ? appraisal.getLeaderOpinion() : leaderOpinion);

        //自动 生成综合成绩
        String synthGrade = getSynthGrade(appraisal.getCorpTeacherGrade(), appraisal.getTeacherGrade());
        appraisal.setSynthGrade((synthGrade == null) ? appraisal.getSynthGrade() : synthGrade);
        boolean update = appraisalService.lambdaUpdate().eq(Appraisal::getStuno, stuno).update(appraisal);
        if (update) {
            //填写时间后台更新操作
            Appraisaldate appraisaldate = appraisaldateService.lambdaQuery().eq(Appraisaldate::getStuno, stuno).one();
            if (appraisaldate == null) {
                appraisaldate = new Appraisaldate();
                appraisaldate.setStuno(stuno);
            }
            appraisaldate.setCorpteacher(StringUtils.isBlank(corpTeacherGrade) ? null : LocalDateTime.now());
            appraisaldate.setTeacher(StringUtils.isBlank(teacherGrade) ? null : LocalDateTime.now());
            appraisaldate.setLeader(StringUtils.isBlank(leaderOpinion) ? null : LocalDateTime.now());
            appraisaldate.setSynth(StringUtils.isBlank(synthGrade) ? null : LocalDateTime.now());
            return ControllerUtil.getTrueOrFalseResult(appraisaldateService.saveOrUpdate(appraisaldate));
        } else {
            return ControllerUtil.getFalseResultMsgBySelf("更新鉴定表失败");
        }
    }

    @Override
    public JsonResult evalStudentReport1(String token, String stuno, String stage1Comment, String stage1Grade) {
        return evalReport(Const.STAGE1, token, stuno, stage1Comment, stage1Grade);
    }

    @Override
    public JsonResult evalStudentReport2(String token, String stuno, String stage2Comment, String stage2Grade) {
        return evalReport(Const.STAGE2, token, stuno, stage2Comment, stage2Grade);
    }

    // 这里没有reportDate 可以设置
    @Override
    public JsonResult evalStudentReportTotal(String token, String stuno, String total_eval) {
        // 总评阶段就不判断report是否为空了, 我觉得这个阶段肯定是一二阶段都填写了,所以有内容
        String teachNo = JWTTool.findToken(token); // teachNo不为空
        Student student = studentService.lambdaQuery().eq(Student::getStuno, stuno).one();
        if (null == student || !student.getTeachno().equals(teachNo)) {
            return ControllerUtil.getFalseResultMsgBySelf("无权访问该学生信息");
        }
        Report report = reportService.lambdaQuery().eq(Report::getStuno, stuno).one();
        report.setTotalEval(StringUtils.isBlank(total_eval) ? report.getTotalEval() : total_eval);
        return ControllerUtil.getTrueOrFalseResult(reportService.saveOrUpdate(report));
    }


    /**
     * 通过导师和校外导师的评价获取综合评价
     * 优秀 良好 中等 及格 不及格
     *
     * @param corpTeacherGrade 校外导师评价
     * @param teacherGrade     导师评价
     * @return
     */
    private String getSynthGrade(String corpTeacherGrade, String teacherGrade) {
        String[] grades = {Const.NO_PASS, Const.PASS, Const.USUAL, Const.GOOD, Const.PERFECT};
        if (StringUtils.isBlank(corpTeacherGrade) || StringUtils.isBlank(teacherGrade)) {
            return null;
        }
        int flag1 = Arrays.asList(grades).indexOf(corpTeacherGrade) + 1;
        int flag2 = Arrays.asList(grades).indexOf(teacherGrade) + 1;
        int flag = Math.min(flag1, flag2);
        if (flag <= 0) {
            flag = 1;// 若是输入的不是5个常量, 判定为差生
        }
        System.out.println(flag + ", " + flag1 + "," + flag2);
        return grades[flag - 1];
    }

    /**
     * 第一阶段和第二阶段的操作类似
     *
     * @param stage
     * @param token
     * @param stuno
     * @param stageComment
     * @param stageGrade
     * @return
     */
    private JsonResult evalReport(int stage, String token, String stuno, String stageComment, String stageGrade) {
        // 交换顺序方式重复代码块警告
        String[] grades = {Const.PASS, Const.NO_PASS, Const.USUAL, Const.GOOD, Const.PERFECT};
        List<String> list = Arrays.asList(grades);
        if (!list.contains(stageGrade) && !StringUtils.isBlank(stageGrade)) {
            stageGrade = Const.NO_PASS;
        }
        Student student = studentService.lambdaQuery().eq(Student::getStuno, stuno).one();
        String teachNo = JWTTool.findToken(token); // teachNo不为空
        if (null == student || !student.getTeachno().equals(teachNo)) {
            return ControllerUtil.getFalseResultMsgBySelf("无权访问该学生信息");
        }
        Report report = reportService.lambdaQuery().eq(Report::getStuno, stuno).one();
        if (report == null) {
            return ControllerUtil.getFalseResultMsgBySelf("无法评价空的报告册表");
        }
        if (stage == Const.STAGE1) {
            report.setStage1Comment(StringUtils.isBlank(stageComment) ? report.getStage1Comment() : stageComment);
            report.setStage1Grade(StringUtils.isBlank(stageGrade) ? report.getStage1Grade() : stageGrade);
        } else {
            report.setStage2Comment(StringUtils.isBlank(stageComment) ? report.getStage2Comment() : stageComment);
            report.setStage2Grade(StringUtils.isBlank(stageGrade) ? report.getStage2Grade() : stageGrade);
        }
        String totalGrade = getSynthGrade(report.getStage1Grade(), report.getStage2Grade());
        report.setTotalGrade(totalGrade);
        boolean update = reportService.lambdaUpdate().eq(Report::getStuno, stuno).update(report);
        if (update) {
            Reportdate reportdate = reportdateService.lambdaQuery().eq(Reportdate::getStuno, stuno).one();
            if (reportdate == null) {
                reportdate = new Reportdate();
                reportdate.setStuno(stuno);
            }
            if (stage == Const.STAGE1) {
                reportdate.setStage1Grade(StringUtils.isBlank(stageGrade) ? null : LocalDateTime.now());
            } else {
                reportdate.setStage2Grade(StringUtils.isBlank(stageGrade) ? null : LocalDateTime.now());
            }
            return ControllerUtil.getTrueOrFalseResult(reportdateService.saveOrUpdate(reportdate));
        } else {
            return ControllerUtil.getFalseResultMsgBySelf("更新报告册失败");
        }
    }

}
