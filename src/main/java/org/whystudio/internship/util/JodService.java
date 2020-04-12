package org.whystudio.internship.util;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.jodconverter.DocumentConverter;
import org.jodconverter.office.OfficeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.whystudio.internship.entity.Pdf;
import org.whystudio.internship.service.IAppraisalService;
import org.whystudio.internship.service.IPdfService;
import org.whystudio.internship.service.IReportService;
import org.whystudio.internship.vo.JodItem;

import javax.annotation.PostConstruct;
import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * word生成服务
 * 准确说这是一个服务，不是tool, 因为其中充满了业务逻辑的味道
 * 但是似乎它又不太属于服务, 暂时放到util包下，后期在改进
 */

@Service
@Slf4j
public class JodService {

    @Autowired
    IReportService reportService;

    @Autowired
    IAppraisalService appraisalService;

    @Autowired
    IPdfService pdfService;

    @Autowired
    DocumentConverter documentConverter;

    /**
     * 实习报告册模板的保存位置
     * classpath(resources)下
     */
    public static String REPORT_PATH = MultiPlatformPathTool.isWindows() ? "word\\report.docx" : "word/report.docx";

    /**
     * 实习鉴定表模板路径
     * classpath(resources)下
     */
    public static String IDENTIFY_PATH = MultiPlatformPathTool.isWindows() ? "word\\identify.docx" : "word/identify.docx";

    /**
     * 基本保存路径
     */
    public static String BASE_SAVE_PATH = MultiPlatformPathTool.isWindows() ? "static\\" : "static/";

    public static Long REC_THRESHOLD = 10L;
    /**
     * 计数器, 计数当前转完完成的任务总数 初始化不为0是为了除
     * 这个计数器和下面的总任务耗时可以单独建立一个线程监听，如果超过某个值就给重置,
     * 但目前没必要, 这个任务难道会超过10亿次？
     * 另外还可以设置异常检测, 如果某次耗时超过30s, 应该是检测异常, 则不计算
     * 不过也没有必要,体现不了任何价值
     */
    public static Long COUNT = 1L;

    /**
     * 总秒数, 转换所有任务总共耗时
     */
    public static Long TOTAL_SECOND = 5L;

    /**
     * 转换队列
     * 通过http向队列添加转换任务
     */
    private static ConcurrentLinkedQueue<JodItem> queue = new ConcurrentLinkedQueue<>();

    public static JodService jodService;

    @PostConstruct
    public void init() {
        jodService = this;
        executeJodTask(); // 开始执行转换任务,
    }

    /**
     * 增加一个转换任务
     *
     * @param jodItem
     */
    public Boolean addTask(JodItem jodItem) {
        if (jodItem == null || StringUtils.isBlank(jodItem.getStuno())) {
            return false;
        }
        queue.offer(jodItem);
        return true;
    }

    /**
     * 获取当前任务的位置
     *
     * @param stuno
     * @return
     */
    public String getPosition(String stuno, Boolean report) {
        int count = 0;
        int size = queue.size();
        for (JodItem jodItem : queue) {
            count++;
            if (jodItem.getStuno().equals(stuno) && jodItem.getReport().equals(report)) {
                break;
            }
        }
        StringBuilder rs = new StringBuilder();
        if (size < count) {
            rs.append("当前队列没有您的任务!");
        } else {
            rs.append("当前队列总共");
            rs.append(size);
            rs.append("个任务,");
            rs.append("您的最近一个任务处于位置");
            rs.append(count);
            rs.append(",预计耗费时间:");
            rs.append((TOTAL_SECOND / COUNT) * count);
        }
        return rs.toString();
    }

    /**
     * 在队列中查找是否存在我的任务
     *
     * @param stuno
     * @return
     */
    public Boolean existMyTask(String stuno, Boolean report) {
        for (JodItem jodItem : queue) {
            if (jodItem.getStuno().equals(stuno) && jodItem.getReport().equals(report)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 跳过头结点位置处任务 一般不会使用
     *
     * @return
     */
    public JodItem popTask() {
        return queue.poll();
    }

    /**
     * 执行word文档生成任务,队列中有多个任务, 一个任务一个任务地执行
     */
    //@Transactional
    public void executeJodTask() {
        new Thread(() -> {
            while (true) {
                JodItem jodItem = queue.poll();
                Pdf pdf = new Pdf();
                pdf.setConverting(true);
                pdf.setUrl("-");
                try {
                    if (jodItem != null) {
                        LocalDateTime start = LocalDateTime.now();
                        log.info("学号{},位置:{}(数值 + 1, 位置0代表1), 开始转换时间:{}", jodItem.getStuno(), getPosition(jodItem.getStuno(), jodItem.getReport()), start);
                        // 转换任务存储到数据库, 并标记状态为未完成
                        // id在new时便已经设置好 暂时使用随机long
                        pdf.setReport(jodItem.getReport());
                        pdf.setStuno(jodItem.getStuno());
                        pdfService.saveOrUpdate(pdf);
                        // 执行转换任务
                        XWPFDocument xwpfDocument;
                        // 从数据库读取report、reportData或者appraisal、appraisalDate, 然后生成params
                        Map<String, String> params;
                        String fileName = MD5Tool.md5(String.valueOf(new Date().getTime()));
                        if (jodItem.getReport()) {
                            params = reportService.getReportInfoInJodFormatByStuno(jodItem.getStuno());
                            if (params.isEmpty()) {
                                continue;
                            }
                            xwpfDocument = readXWPFDocumentFromFile(REPORT_PATH);
                        } else {
                            params = appraisalService.getAppraisalInfoInJodFormatByStuno(jodItem.getStuno());
                            if (params.isEmpty()) {
                                continue;
                            }
                            xwpfDocument = readXWPFDocumentFromFile(IDENTIFY_PATH);
                        }
                        replaceInTable(xwpfDocument, params);
                        //存到本地临时文件夹
                        File sourceDocx = new File(BASE_SAVE_PATH + fileName + ".docx");
                        if (!sourceDocx.exists()) {
                            sourceDocx.createNewFile();
                        }
                        FileOutputStream outputStream
                                = new FileOutputStream(sourceDocx);
                        xwpfDocument.write(outputStream);
                        // 转换成pdf
                        File targetPdf = new File(BASE_SAVE_PATH + fileName + ".pdf");
                        documentConverter.convert(sourceDocx).to(targetPdf).execute();
                        // 上传到七牛云
                        String url = QiNiuTool.uploadQiNiu(new FileInputStream(targetPdf), targetPdf.getName());

                        pdf.setUrl(url);
                        pdf.setConverting(false);
                        pdfService.saveOrUpdate(pdf);
                        // 嘿嘿
                        Long durex = Duration.between(start, LocalDateTime.now()).getSeconds();
                        if (durex > REC_THRESHOLD) {
                            // 大于10s的才记录
                            COUNT++;
                            TOTAL_SECOND += Duration.between(start, LocalDateTime.now()).getSeconds();
                        }
                        log.info("学号{},转换加上传到QiNiu耗时:{}s", jodItem.getStuno(),
                                Duration.between(start, LocalDateTime.now()).getSeconds());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    pdf.setFailed(true);
                    pdfService.saveOrUpdate(pdf);
                    continue;
                }
                // 如果队列中没有任务, 就让线程休息一会儿
                if (queue.size() < 1) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        log.error(e.getMessage());
                    }
                }
            }
        }).start();
    }

    // ----- word处理方法 ----- //

    /**
     * 渲染并导出word文档, 返回输出路径.
     *
     * @param renderWordType :  "report" or "identify"
     * @param params         :  渲染Word的参数
     * @return: java.lang.String
     */
    public String exportWordToResponse(String renderWordType, String stuNo, Map<String, String> params) {
        XWPFDocument xwpfDocument;
        StringBuffer fileName = new StringBuffer(stuNo);
        try {
            if (renderWordType.equals("report")) {
                xwpfDocument = readXWPFDocumentFromFile(REPORT_PATH);
                fileName.append("_report_");
            } else {
                xwpfDocument = readXWPFDocumentFromFile(IDENTIFY_PATH);
                fileName.append("_identify_");
            }
            fileName.append(System.currentTimeMillis());
            replaceInTable(xwpfDocument, params);
            String savePath = printToFile(xwpfDocument, fileName.toString(), BASE_SAVE_PATH);
            if (savePath != null) {
                return savePath;
            }
        } catch (Exception e) {
            log.error(e.toString());
        }
        return null;
    }


    /**
     * 通过路径读入word模板文件
     *
     * @param path word模板文件存储的位置
     * @return 返回WXPFDocument对象(word)
     * @throws IOException 流异常/xwpf异常
     */
    public XWPFDocument readXWPFDocumentFromFile(String path) throws IOException {
        Resource resource = new ClassPathResource(path);
        InputStream inputStream = resource.getInputStream();
        XWPFDocument xwpfDocument = new XWPFDocument(inputStream);
        close(inputStream);
        return xwpfDocument;
    }

    /**
     * 替换表格里面的变量 ${variable}
     *
     * @param doc
     * @param params
     */
    public void replaceInTable(XWPFDocument doc, Map<String, String> params) {
        Iterator<XWPFTable> iterator = doc.getTablesIterator();
        XWPFTable table;
        List<XWPFTableRow> rows;
        List<XWPFTableCell> cells;
        List<XWPFParagraph> paras;
        while (iterator.hasNext()) {
            table = iterator.next();
            rows = table.getRows();
            for (XWPFTableRow row : rows) {
                cells = row.getTableCells();
                for (XWPFTableCell cell : cells) {
                    paras = cell.getParagraphs();
                    for (XWPFParagraph para : paras) {
                        this.replaceInPara(para, params);
                    }
                }
            }
        }
    }

    /**
     * 将word docx文件保存到本地文件中
     *
     * @param docx     当前编辑的docx文件
     * @param fileName 要保存的docx文件的名字,不包括后缀<b>.docx</b>
     * @param path
     */
    public String printToFile(XWPFDocument docx, String fileName, String path) {
        if (MultiPlatformPathTool.isWindows()) {
            path = System.getProperty("user.dir") + "\\" + path;
        } else {
            path = System.getProperty("user.dir") + "/" + path;
        }

        fileName += ".docx";
        try {
            File file = new File(path + fileName);
            if (!file.exists()) {
                file.createNewFile();
            }
            FileOutputStream outputStream = new FileOutputStream(file);
            docx.write(outputStream);
            close(outputStream);
            return fileName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 替换段落里面的变量 变量形式： ${variable}
     *
     * @param para   传入一个段落
     * @param params 传入变量以及变量的值
     */
    private void replaceInPara(XWPFParagraph para, Map<String, String> params) {
        if (this.matcher(para.getParagraphText()).find()) {
            // 将paragraph中的变量内容替换成变量的值，然后在换回去
            Set<String> keys = params.keySet();
            String content = para.getParagraphText();
            StringBuilder contentRs;
            for (String k : keys) {
                // 包含子串
                if (para.getParagraphText().indexOf(k) != -1) {

                    // 在删除旧的run之前需要将带有daoler符号的那个run的格式全部记录下来。
                    List<XWPFRun> runs = para.getRuns();

                    // 初始化需要保存的变量
                    int textPosition = 0;
                    int fontSize = 0;
                    String fontFamily = "";
                    // 使用会抛出异常
                    // int    characterSpacing = 0;
                    String color = "";
                    int kerning = 0;
                    UnderlinePatterns underline = null;
                    VerticalAlign subscript = null;
                    for (XWPFRun run : runs) {
                        // $符号是变量起始位置的符号,这个$符的格式就是变量在word中的格式
                        if (-1 != run.text().indexOf("$")) {
                            textPosition = run.getTextPosition();
                            fontSize = run.getFontSize();
                            fontFamily = run.getFontFamily();
                            // characterSpacing = run.getCharacterSpacing(); //使用会抛出异常
                            color = run.getColor();
                            kerning = run.getKerning();
                            underline = run.getUnderline();
                            subscript = run.getSubscript();
                        }
                    }
                    // 设置新的run之前删除旧的run
                    int size = para.getRuns().size();
                    while (size > 0) {
                        para.removeRun(0);
                        size = para.getRuns().size();
                    }
                    para.removeRun(0);  //这一句是多余的吧?既生之,何弃之.

                    // 设置新的run
                    int start = content.indexOf(k);
                    int end = start + k.length();
                    contentRs = new StringBuilder();
                    contentRs.append(content, 0, start);
                    //判断是否为空，避免出现"null"字符串
                    if (params.get(k) == null) {
                        contentRs.append("  ");
                    } else {
                        contentRs.append(params.get(k));
                    }
                    contentRs.append(content.substring(end));
                    para.insertNewRun(0).setText(contentRs.toString());
                    // 设置para的run的格式等内容
                    para.getRuns().get(0).setFontFamily(fontFamily);
                    para.getRuns().get(0).setFontSize(fontSize);
                    // para.getRuns().get(0).setCharacterSpacing(characterSpacing);//使用会抛出异常
                    para.getRuns().get(0).setColor(color);
                    para.getRuns().get(0).setUnderline(underline);
                    para.getRuns().get(0).setTextPosition(textPosition);
                    para.getRuns().get(0).setKerning(kerning);
                    para.getRuns().get(0).setSubscript(subscript);
                }
            }
        }
    }

    private Matcher matcher(String str) {
        String regStr = "\\$\\{(.+?)\\}";
        Pattern pattern = Pattern.compile(regStr, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(str);
        return matcher;
    }

    public void close(InputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void close(OutputStream outputStream) {
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
